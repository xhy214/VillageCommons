package io.github.xhy214.village;

import com.google.gson.Gson;
import io.github.xhy214.village.ai.*;
import io.github.xhy214.village.core.*;
import io.github.xhy214.village.data.*;
import io.github.xhy214.village.ui.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class VillageCommonsPlugin extends JavaPlugin implements Listener,CommandExecutor,TabCompleter {
    private final CommonsEngine engine=new CommonsEngine();
    private final ExecutorService apiWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"VillageCommons-API");t.setDaemon(true);return t;});
    private final ExecutorService ioWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"VillageCommons-save");t.setDaemon(true);return t;});
    private final Deque<RoundRecord> history=new ArrayDeque<>();
    private WorldState state;private StateStore store;private ApiClient api;private ArenaView arena;private MenuController menus;
    private volatile boolean stopping;private boolean busy,auto;private int generation;
    private Future<?> active;private BukkitTask timer;private long nextAutoAt;
    private String notice="等待第一次收割",runId=UUID.randomUUID().toString().substring(0,8);
    @Override public void onEnable() {
        saveDefaultConfig();store=new StateStore(getDataFolder().toPath());
        try{state=store.load();}catch(Exception e){getLogger().warning(e.getMessage());state=WorldState.initial();}
        ApiSettings settings;
        try{settings=ApiSettings.read(getDataFolder().toPath().resolve("api.local.json"));}
        catch(Exception e){settings=ApiSettings.empty();getLogger().warning(e.getMessage());}
        api=new ApiClient(settings);arena=new ArenaView(this);menus=new MenuController(this);
        try{arena.initialize(getConfig().getString("world-name","commons_demo"),state);}
        catch(Exception e){getLogger().severe("共享麦田初始化失败："+e);getServer().getPluginManager().disablePlugin(this);return;}
        Objects.requireNonNull(getCommand("village")).setExecutor(this);getCommand("village").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this,this);getServer().getPluginManager().registerEvents(menus,this);
        timer=Bukkit.getScheduler().runTaskTimer(this,()->{
            menus.refreshOpen();
            if(auto && !busy && !arena.world().getPlayers().isEmpty() && System.currentTimeMillis()>=nextAutoAt)nextRound(null);
        },20,20);
        getLogger().info("VC_READY world="+arena.world().getName()+" villagers=4 farmland=48 API="+(settings.configured()?"configured":"not-configured"));
        persist("startup");
    }
    public WorldState state(){return state;}public boolean busy(){return busy;}public boolean automatic(){return auto;}
    public ApiClient.Status apiStatus(){return api.status();}public ArenaView arena(){return arena;}
    public String notice(){return notice;}public List<RoundRecord> history(){return List.copyOf(history);}
    private boolean allowed(CommandSender s){return !(s instanceof Player)||getConfig().getBoolean("allow-local-mayor",false)||s.hasPermission("villagecommons.manage");}
    public void say(CommandSender p,String message){if(p!=null)p.sendMessage(Component.text("[麦风村] ",NamedTextColor.GOLD).append(Component.text(message,NamedTextColor.WHITE)));}
    private void announce(String message){getLogger().info(message);for(Player p:arena.world().getPlayers())say(p,message);}
    private boolean editable(CommandSender p) {
        if(!allowed(p)){say(p,"需要市长权限");return false;}
        if(busy){say(p,"本回合尚未完成，可继续查看村民");return false;}return true;
    }
    public void nextRound(CommandSender p) {
        if(!editable(p))return;busy=true;int ticket=++generation;WorldState prepared=engine.prepare(state);
        notice=api.status().configured()?"四名村民正在自主决定收割量…":"正在检查API配置…";
        arena.refresh(state,true,notice);menus.refreshOpen();say(p,"第"+prepared.round+"回合开始");
        active=apiWorker.submit(()->{
            try {
                ApiClient.Result result=api.decide(prepared);if(stopping)return;
                Bukkit.getScheduler().runTask(this,()->{
                    if(stopping||ticket!=generation)return;
                    if(!result.success()){paused(ticket,prepared.round,result);return;}
                    try {
                        CommonsEngine.Outcome outcome=engine.settle(prepared,result.decision());
                        notice="村民正在收割，随后恢复麦田…";arena.refresh(state,true,notice);menus.refreshOpen();
                        arena.animate(outcome,()->{
                            if(stopping||ticket!=generation)return;
                            state=outcome.state();notice=result.notice();
                            RoundRecord record=new RoundRecord(Instant.now().toString(),runId,result.source(),result.notice(),
                                result.requests(),result.elapsedMs(),result.promptTokens(),result.completionTokens(),result.usageReported(),
                                state.copy(),result.decision().actions(),outcome.harvests(),outcome.regrown(),outcome.notes());
                            history.addFirst(record);while(history.size()>12)history.removeLast();WorldState snapshot=state.copy();
                            ioWorker.submit(()->{try{store.append(record);store.save(snapshot);}catch(Exception e){getLogger().warning("保存回合失败："+e.getMessage());}});
                            busy=false;nextAutoAt=System.currentTimeMillis()+interval()*1000L;arena.refresh(state,false,notice);menus.refreshOpen();
                            announce("第"+state.round+"回合 · "+state.profileName()+" · "+state.lastMode+" · 收割"+state.lastHarvest+" · 恢复"+state.lastRegrowth+" · 麦田"+state.mature()+"/48 · 缺粮"+state.lastShortages+"人");
                            getLogger().info("VC_ROUND source="+result.source()+" ai_profile="+state.aiProfile.name().toLowerCase(Locale.ROOT)+" requests="+result.requests()+" parameters="+api.status().parameters()+" parameters_accepted="+api.status().parametersAccepted()+" latency_ms="+result.elapsedMs());
                        });
                    }catch(Exception e){failed(ticket,"本回合未提交，已有存档保持不变",e);}
                });
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(Exception e){if(!stopping)Bukkit.getScheduler().runTask(this,()->failed(ticket,"本回合未完成，可重新推进",e));}
        });
    }
    private void paused(int ticket,int attemptedRound,ApiClient.Result result) {
        if(ticket!=generation)return;busy=false;auto=false;
        notice="本回合未结算："+result.notice()+"；自动已暂停，资源保持不变";
        arena.cancelAnimation();arena.refresh(state,false,notice);menus.refreshOpen();announce(notice);
        getLogger().info("VC_PAUSED attempted_round="+attemptedRound+" source="+result.source()+" ai_profile="+
            state.aiProfile.name().toLowerCase(Locale.ROOT)+" requests="+result.requests()+" latency_ms="+result.elapsedMs());
        persist("AI_PAUSED R"+attemptedRound+" "+result.notice()+" requests="+result.requests());
    }
    private void failed(int ticket,String message,Exception e) {
        if(ticket!=generation)return;busy=false;auto=false;notice=message;arena.cancelAnimation();arena.refresh(state,false,notice);
        menus.refreshOpen();getLogger().severe("回合失败："+e.getMessage());announce(message);
    }
    private int interval(){return Math.clamp(getConfig().getInt("auto-interval-seconds",5),5,300);}
    public void toggleAuto(CommandSender p){if(!allowed(p))return;auto=!auto;nextAutoAt=System.currentTimeMillis()+interval()*1000L;say(p,auto?"自动回合开启；村内无人时暂停":"自动回合已暂停");menus.refreshOpen();}
    public void speed(CommandSender p,int seconds) {
        if(!editable(p))return;getConfig().set("auto-interval-seconds",seconds);saveConfig();
        nextAutoAt=System.currentTimeMillis()+interval()*1000L;say(p,"自动回合间隔："+interval()+"秒");menus.refreshOpen();
    }
    public void reset(CommandSender p) {
        reset(p,state.aiProfile);
    }
    private void reset(CommandSender p,WorldState.AiProfile profile) {
        if(!allowed(p))return;generation++;if(active!=null)active.cancel(true);busy=false;auto=false;
        arena.resetPositions();state=WorldState.initial(profile);history.clear();runId=UUID.randomUUID().toString().substring(0,8);
        notice="实验已重置，旧日志与本次启动API计数保留";changed(p,notice);
    }
    public void scenario(CommandSender p,String which) {
        if(!allowed(p))return;reset(p,which.equals("ai-greedy")?WorldState.AiProfile.GREEDY:WorldState.AiProfile.NORMAL);
        changed(p,state.profileName()+"实验：48株麦田，每人1份初始口粮；AI自主决定收割量");
    }
    private void changed(CommandSender p,String text){persist(text);arena.refresh(state,busy,notice);menus.refreshOpen();say(p,text);}
    private void persist(String action) {
        WorldState snapshot=state.copy();String id=runId;
        ioWorker.submit(()->{try{store.event(id,action,snapshot);store.save(snapshot);}catch(Exception e){getLogger().warning("存档保存失败："+e.getMessage());}});
    }
    @Override public boolean onCommand(CommandSender p,Command c,String label,String[] args) {
        if(arena==null||arena.world()==null){say(p,"村庄尚未准备好");return true;}
        try {
            if(args.length==0){if(p instanceof Player player)menus.openMayor(player);else say(p,"village next | status | scenario ai/ai-greedy | auto on/off | speed 5..300 | reset confirm");return true;}
            switch(args[0].toLowerCase(Locale.ROOT)) {
                case "next"->nextRound(p);
                case "status"->{
                    say(p,state.profileName()+" R"+state.round+" 麦田"+state.mature()+"/48 收割"+state.lastHarvest+" 恢复"+state.lastRegrowth+" 缺粮"+state.lastShortages);
                    if(!(p instanceof Player))p.sendMessage("VC_STATUS "+new Gson().toJson(Map.of("state",state,"api",api.status(),"busy",busy,"auto",auto,"runId",runId,"actualMature",arena.actualMature())));
                }
                case "scenario"->{if(args.length<2||!Set.of("ai","ai-greedy").contains(args[1]))throw new IllegalArgumentException();scenario(p,args[1]);}
                case "auto"->{boolean desired=onOff(args,1);if(desired!=auto)toggleAuto(p);}
                case "speed"->speed(p,number(args,1,5,300));
                case "reset"->{if(args.length>1&&args[1].equals("confirm"))reset(p,state.aiProfile);else say(p,"使用 /village reset confirm 重置当前实验，旧日志保留");}
                case "tp"->{if(p instanceof Player player)player.teleport(arena.spawnLocation());}
                case "history"->{if(p instanceof Player player)menus.openHistory(player);else for(var r:history)say(p,"R"+r.state().round+" "+r.source()+" mature="+r.state().mature());}
                case "reload"->{if(editable(p)){api.configure(ApiSettings.read(getDataFolder().toPath().resolve("api.local.json")));say(p,"API配置已重载，累计请求数保留");}}
                default->say(p,"使用 /village 打开共享麦田面板");
            }
        }catch(Exception e){say(p,"参数无效，请查README命令表；API配置请检查api.local.json");}
        return true;
    }
    private int number(String[] a,int i,int min,int max){int n=Integer.parseInt(a[i]);if(n<min||n>max)throw new IllegalArgumentException();return n;}
    private boolean onOff(String[] a,int i){if(!Set.of("on","off").contains(a[i]))throw new IllegalArgumentException();return a[i].equals("on");}
    @Override public List<String> onTabComplete(CommandSender p,Command c,String label,String[] a) {
        List<String> values=a.length==1?List.of("next","status","scenario","auto","speed","reset","tp","history","reload"):
            a.length==2?switch(a[0]){case "scenario"->List.of("ai","ai-greedy");case "auto"->List.of("on","off");case "reset"->List.of("confirm");case "speed"->List.of("5","10","20");default->List.of();}:List.of();
        return values.stream().filter(v->v.startsWith(a[a.length-1])).toList();
    }
    @EventHandler public void join(PlayerJoinEvent e) {
        Player p=e.getPlayer();say(p,"欢迎来到共享麦田。输入 /village；观察模式可双击空格飞行");
        if(getConfig().getBoolean("allow-local-mayor",false))Bukkit.getScheduler().runTaskLater(this,()->{
            if(p.isOnline()){p.teleport(arena.spawnLocation());p.setGameMode(GameMode.ADVENTURE);p.setAllowFlight(true);p.setFoodLevel(20);}
        },10);
    }
    @EventHandler public void interact(PlayerInteractEntityEvent e) {
        String id=arena.agentId(e.getRightClicked());if(id!=null&&state.agent(id)!=null){e.setCancelled(true);if(e.getHand()==EquipmentSlot.HAND)menus.openAgent(e.getPlayer(),id);}
    }
    @EventHandler public void damage(EntityDamageEvent e){if(arena.inside(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler public void hunger(FoodLevelChangeEvent e){if(arena.inside(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler public void blockBreak(BlockBreakEvent e){if(arena.inside(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void blockPlace(BlockPlaceEvent e){if(arena.inside(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler public void trample(EntityInteractEvent e){if(arena.inside(e.getBlock().getLocation())&&e.getBlock().getType()==Material.FARMLAND)e.setCancelled(true);}
    @EventHandler public void physical(PlayerInteractEvent e) {
        if(e.getClickedBlock()!=null&&arena.inside(e.getClickedBlock().getLocation()) &&
            (e.getAction()==org.bukkit.event.block.Action.PHYSICAL || e.getClickedBlock().getState() instanceof org.bukkit.inventory.InventoryHolder))e.setCancelled(true);
    }
    @Override public void onDisable() {
        stopping=true;generation++;if(timer!=null)timer.cancel();if(arena!=null)arena.stop();
        if(active!=null)active.cancel(true);apiWorker.shutdownNow();if(api!=null)api.close();
        if(state!=null&&store!=null)persist("shutdown");ioWorker.shutdown();
        try{ioWorker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
