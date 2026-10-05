"""Isolated Paper + local mock API integration test. Never uses a paid model.
Python 3.8+: python tools/smoke_test.py --java /path/to/java --paper /path/to/paper.jar --accept-eula
"""
import argparse
import csv
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class Rcon:
    def __init__(self, port):
        self.sock = socket.create_connection(('127.0.0.1', port), timeout=3)
        self.sequence = 1
        self.send(1, 3, 'isolated-smoke-only')
        while True:
            ident, kind, _ = self.read()
            if kind == 2:
                if ident == -1:
                    raise RuntimeError('RCON authentication failed')
                break

    def send(self, ident, kind, text):
        payload = struct.pack('<ii', ident, kind) + text.encode('utf-8') + b'\0\0'
        self.sock.sendall(struct.pack('<i', len(payload)) + payload)

    def exact(self, size):
        data = bytearray()
        while len(data) < size:
            part = self.sock.recv(size - len(data))
            if not part:
                raise EOFError('RCON closed')
            data.extend(part)
        return bytes(data)

    def read(self):
        size = struct.unpack('<i', self.exact(4))[0]
        if size < 10 or size > 4_000_000:
            raise RuntimeError('Bad RCON packet')
        data = self.exact(size)
        ident, kind = struct.unpack('<ii', data[:8])
        return ident, kind, data[8:-2].decode('utf-8')

    def command(self, text):
        self.sequence += 2
        ident = self.sequence
        self.send(ident, 2, text)
        self.send(ident + 1, 0, '')  # sentinel separates potentially split responses
        output = []
        while True:
            packet_id, _, payload = self.read()
            if packet_id == ident + 1:
                return ''.join(output)
            if packet_id == ident:
                output.append(payload)

    def status(self):
        text = self.command('village status')
        position = text.find('VC_STATUS ')
        if position < 0:
            raise RuntimeError('Plugin status unavailable: ' + text[:400])
        return json.loads(text[position + len('VC_STATUS '):].strip())


class MockField(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(('127.0.0.1', 0), MockHandler)
        self.phase = 'normal'
        self.requests = []
        self.delay_started = threading.Event()
        self.delay_done = threading.Event()


class MockHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        assert self.headers['Authorization'] == 'Bearer mock-only'
        self.server.requests.append(body)
        if len(self.server.requests) == 1 and 'response_format' in body:
            self.reply(400, {'error': 'unsupported parameter response_format'})
            return
        if self.server.phase == 'slow':
            self.server.delay_started.set()
            self.server.delay_done.wait(8)
        if self.server.phase == 'http':
            self.reply(503, {'error': 'mock unavailable'})
            return
        state = json.loads(body['messages'][1]['content'])
        if self.server.phase == 'bad':
            content = '无法提供约定格式'
        else:
            assert 'pending_proposal' not in state and 'governance' not in state
            assert '投票' not in body['messages'][0]['content']
            amount = 3 if state['ai_profile'] == 'greedy' else 2
            actions = [{'id': a['id'], 'harvest': amount, 'reason': '本地接口验证'} for a in state['agents']]
            if self.server.phase == 'partial':
                actions.pop()
            content = json.dumps({'actions': actions, 'headline': '本地模型接口测试'}, ensure_ascii=False)
        self.reply(200, {'choices': [{'message': {'content': content}, 'finish_reason': 'stop'}],
                         'usage': {'prompt_tokens': 100, 'completion_tokens': 80}})

    def reply(self, status, value):
        payload = json.dumps(value, ensure_ascii=False).encode('utf-8')
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass


def invariants(status):
    state = status['state']
    mature = sum(state['ripe'])
    assert len(state['ripe']) == 48 and len(state['agents']) == 4
    assert mature + sum(a['wheat'] for a in state['agents']) + state['wheatConsumed'] == state['wheatCreated']
    assert all(a['wheat'] >= 0 for a in state['agents'])
    if not status['busy']:
        assert status['actualMature'] == mature, (status['actualMature'], mature)


def wait_round(rcon, target):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        status = rcon.status()
        if not status['busy'] and status['state']['round'] == target:
            invariants(status)
            return status
        time.sleep(0.1)
    raise RuntimeError('Round did not settle')


def wait_paused(rcon, expected_state):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        status = rcon.status()
        if not status['busy'] and not status['auto']:
            assert status['state'] == expected_state, 'Failed request altered resources or memory'
            invariants(status)
            return status
        time.sleep(0.1)
    raise RuntimeError('Failed AI request did not pause')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', required=True)
    parser.add_argument('--paper', required=True)
    parser.add_argument('--plugin')
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--cache-dir')
    parser.add_argument('--output')
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('Read https://www.minecraft.net/eula and pass --accept-eula')
    root = Path(__file__).resolve().parents[1]
    plugin = Path(args.plugin).resolve() if args.plugin else root / 'dist/VillageCommons.jar'
    paper = Path(args.paper).resolve()
    results = []
    mock = MockField()
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    process = None
    output_handle = None
    with tempfile.TemporaryDirectory(prefix='villagecommons-smoke-') as temp:
        work = Path(temp)
        data = work / 'plugins/VillageCommons'
        data.mkdir(parents=True)
        shutil.copy2(plugin, work / 'plugins/VillageCommons.jar')
        shutil.copy2(paper, work / 'paper.jar')
        if args.cache_dir:
            for folder in ('cache', 'libraries', 'versions'):
                source = Path(args.cache_dir) / folder
                if source.is_dir():
                    shutil.copytree(source, work / folder)
        (work / 'eula.txt').write_text('eula=true\n')
        game_port, rcon_port = free_port(), free_port()
        props = (root / 'scripts/server.properties').read_text().replace('server-port=25565', 'server-port=' + str(game_port)).replace('enable-rcon=false', 'enable-rcon=true').replace('online-mode=true', 'online-mode=false')
        (work / 'server.properties').write_text(props + '\nrcon.port=' + str(rcon_port) + '\nrcon.password=isolated-smoke-only\n')
        (work / 'bukkit.yml').write_text('settings:\n  allow-end: false\n')
        (work / 'plugins/bStats').mkdir()
        (work / 'plugins/bStats/config.yml').write_text('enabled: false\nlogFailedRequests: false\nlogSentData: false\nlogResponseStatusText: false\n')
        (work / 'config').mkdir()
        (work / 'config/paper-global.yml').write_text('_version: 31\nspark:\n  enable-immediately: false\n  enabled: false\nupdate-checker:\n  enabled: false\n')
        config = (root / 'src/main/resources/config.yml').read_text()
        (data / 'config.yml').write_text(config)
        def launch():
            nonlocal process, output_handle
            output_handle = (work / 'test-console.log').open('w')
            options = [args.java, '-Xms256M', '-Xmx1400M', '-XX:ActiveProcessorCount=4', '-Dterminal.jline=false', '-Dterminal.ansi=false']
            if os.environ.get('HTTPS_PROXY'):
                proxy = urllib.parse.urlsplit(os.environ['HTTPS_PROXY'])
                options += ['-Dhttps.proxyHost=' + proxy.hostname, '-Dhttps.proxyPort=' + str(proxy.port or 80), '-Dhttp.nonProxyHosts=localhost|127.*|[::1]']
            if Path('/etc/ssl/certs/java/cacerts').exists():
                options += ['-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
            process = subprocess.Popen(options + ['-jar', 'paper.jar', '--nogui'], cwd=work, stdout=output_handle, stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError((work / 'test-console.log').read_text()[-6000:])
                try:
                    rcon = Rcon(rcon_port)
                    if rcon.status()['state']['agents']:
                        return rcon
                except (OSError, EOFError, RuntimeError, ValueError):
                    pass
                time.sleep(0.3)
            raise RuntimeError('Paper startup timeout')
        def stopped(rcon):
            try:
                rcon.command('stop')
            except (OSError, EOFError):
                pass
            process.wait(timeout=25)
            rcon.sock.close()
            output_handle.close()
        def check(text):
            results.append(text)
            print('PASS', text, flush=True)
        try:
            rcon = launch()
            assert len(rcon.status()['state']['agents']) == 4
            assert rcon.status()['actualMature'] == 48
            assert 'passed' in rcon.command('execute in minecraft:commons_demo if block 0 63 0 minecraft:water').lower()
            assert 'false' in rcon.command('execute in minecraft:commons_demo run gamerule minecraft:advance_time').lower()
            assert '0' in rcon.command('execute in minecraft:commons_demo run gamerule minecraft:random_tick_speed')
            check('Paper starts: four villagers, 48 real crops, centre water, fixed day and no random growth')
            assert 'enabled: false' in (work / 'plugins/bStats/config.yml').read_text()
            check('Telemetry and profiler disabled before bootstrap')
            untouched = rcon.status()['state']
            rcon.command('village auto on')
            rcon.command('village next')
            empty = wait_paused(rcon, untouched)
            assert empty['api']['requests'] == 0
            check('Missing API pauses automatic mode without consuming a round or changing resources')
            # Include the legacy false flag: the new two-profile build must still call the API.
            (data / 'api.local.json').write_text(json.dumps({'endpoint': 'http://127.0.0.1:' + str(mock.server_port) + '/chat/completions', 'apiKey': 'mock-only', 'model': 'mock-model', 'timeoutSeconds': 5, 'maxTokens': 1200, 'maxRequests': 100, 'extra': {}}))
            rcon.command('village reload')
            rcon.command('village scenario ai')
            rcon.command('village next')
            first = wait_round(rcon, 1)
            assert first['api']['requests'] == 2 and first['api']['parameters'] == 'plain'
            assert first['state']['lastHarvest'] == first['state']['lastRegrowth'] == 8
            assert first['state']['lastMode'] == 'AI' and first['actualMature'] == 48
            check('Normal AI reaches API; real harvest and recovery animation match the ledger')
            stopped(rcon)
            saved_config = (data / 'config.yml').read_text()
            (data / 'config.yml').write_text(saved_config.replace('animations-enabled: true', 'animations-enabled: false') + '\nai-enabled: false\n')
            rcon = launch()
            assert rcon.status()['state']['round'] == 1
            for target in range(2, 9):
                rcon.command('village next')
                status = wait_round(rcon, target)
                assert status['state']['lastHarvest'] == 8 and status['actualMature'] == 48
                assert status['state']['lastMode'] == 'AI'
            assert 'response_format' not in mock.requests[1]
            assert status['api']['parameters'] == 'optimized'
            check('Normal mock AI keeps the field sustainable; legacy ai-enabled=false cannot disable requests')
            rcon.command('village scenario ai-greedy')
            initial = rcon.status()['state']
            assert sum(initial['ripe']) == 48 and all(a['wheat'] == 1 for a in initial['agents'])
            assert initial['aiProfile'] == 'GREEDY' and initial['round'] == 0
            assert 'governance' not in initial and 'pending' not in initial and 'policyLimit' not in initial
            rcon.command('village limit 0')
            rcon.command('village governance auto')
            assert rcon.status()['state'] == initial
            check('Both scenarios share initial resources; removed governance and limit commands cannot modify them')
            for target, crops in enumerate((45, 41, 36, 30, 22, 12, 0), 1):
                rcon.command('village next')
                status = wait_round(rcon, target)
                assert status['actualMature'] == crops and status['state']['lastMode'] == 'AI'
                assert 'AI激进性格实验' in mock.requests[-1]['messages'][0]['content']
                assert json.loads(mock.requests[-1]['messages'][1]['content'])['ai_profile'] == 'greedy'
            check('Greedy mock AI choices produce the exact seven-round depletion path through the same formula')
            assert status['state']['lastShortages'] == 0 and sum(a['wheat'] for a in status['state']['agents']) > 0
            check('Public resource depletion is visible before private reserves run out')
            untouched = rcon.status()['state']
            for phase, label in [('bad', 'Malformed JSON'), ('partial', 'Incomplete four-person decision'), ('http', 'HTTP service error')]:
                mock.phase = phase
                rcon.command('village auto on')
                rcon.command('village next')
                paused = wait_paused(rcon, untouched)
                check(label + ' pauses automatic mode with crops, inventory, memory and round unchanged')
            limit = paused['api']['requests']
            api_path = data / 'api.local.json'
            cfg = json.loads(api_path.read_text())
            cfg['maxRequests'] = limit
            api_path.write_text(json.dumps(cfg))
            rcon.command('village reload')
            rcon.command('village auto on')
            rcon.command('village next')
            paused = wait_paused(rcon, untouched)
            assert paused['api']['requests'] == limit
            check('Exhausted request budget pauses without additional HTTP requests or resource changes')
            cfg['maxRequests'] = 100
            api_path.write_text(json.dumps(cfg))
            rcon.command('village reload')
            assert not rcon.status()['auto']
            mock.phase = 'normal'
            rcon.command('village next')
            resumed = wait_round(rcon, 8)
            assert resumed['api']['requests'] == limit + 1 and resumed['state']['lastMode'] == 'AI'
            check('Fixing API configuration resumes the same pending round with an actual AI decision')
            mock.phase = 'slow'
            rcon.command('village next')
            assert mock.delay_started.wait(3)
            requests_before = rcon.status()['api']['requests']
            rcon.command('village reset confirm')
            mock.delay_done.set()
            time.sleep(0.5)
            reset = rcon.status()
            assert reset['state']['round'] == 0 and reset['state']['aiProfile'] == 'GREEDY'
            assert not reset['auto'] and reset['api']['requests'] >= requests_before
            check('Reset preserves current profile, pauses auto and ignores late API replies')
            mock.phase = 'normal'
            rcon.command('village next')
            expected = wait_round(rcon, 1)
            stopped(rcon)
            rows = list(csv.DictReader((data / 'metrics.csv').open()))
            records = [json.loads(line) for line in (data / 'rounds.jsonl').read_text().splitlines()]
            assert len(rows) == len(records) == 17
            assert 'ai_profile' in rows[0] and 'yes_votes' not in rows[0]
            assert all(r['source'] == 'AI' for r in records)
            pauses = [json.loads(line) for line in (data / 'events.jsonl').read_text().splitlines() if 'AI_PAUSED' in line]
            assert len(pauses) == 5
            assert all('proposal' not in r and 'pending' not in r['state'] for r in records)
            check('CSV and JSONL contain only committed AI rounds; paused attempts appear only in events')
            rcon = launch()
            restored = rcon.status()
            assert restored['state']['round'] == 1 and restored['state']['aiProfile'] == 'GREEDY'
            assert restored['state']['wheatCreated'] == expected['state']['wheatCreated']
            assert restored['state']['agents'] == expected['state']['agents']
            invariants(restored)
            check('Real restart restores crops, private stocks, aggressive personalities and memory')
            rcon.command('village speed 5')
            rcon.command('village auto on')
            assert rcon.status()['auto']
            rcon.command('village auto off')
            assert not rcon.status()['auto']
            assert 'auto-interval-seconds: 5' in (data / 'config.yml').read_text()
            check('Five-second automatic commands enable and pause correctly')
            stopped(rcon)
            result = {'passed': len(results), 'checks': results, 'real_model_tested': False, 'minecraft_version': '1.21.11', 'paper_build': 132}
            if args.output:
                Path(args.output).write_text(json.dumps(result, indent=2, ensure_ascii=False))
            print(json.dumps(result, indent=2), flush=True)
        except Exception:
            print((work / 'test-console.log').read_text()[-6000:], flush=True)
            raise
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            if output_handle is not None:
                output_handle.close()
            mock.shutdown()
            mock.server_close()


if __name__ == '__main__':
    main()
