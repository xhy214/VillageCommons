package io.github.xhy214.village.ui;
import io.github.xhy214.village.VillageCommonsPlugin;
import io.github.xhy214.village.core.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;

public final class MenuController implements Listener {
    private enum Kind { FIELD,AGENT,HISTORY }
    private static final class Menu implements InventoryHolder {
        final Kind kind;final String id;final Inventory inventory;
        Menu(Kind kind,String id,int size,String title){this.kind=kind;this.id=id;inventory=Bukkit.createInventory(this,size,Component.text(title));}
        @Override public Inventory getInventory(){return inventory;}
    }
    private final VillageCommonsPlugin plugin;
    private final Map<UUID,String> confirm=new HashMap<>();
    private final Map<UUID,Long> confirmedAt=new HashMap<>();
    public MenuController(VillageCommonsPlugin plugin){this.plugin=plugin;}
    public void openMayor(Player p){open(p,new Menu(Kind.FIELD,"",45,"麦风村 · AI收割实验"));}
    public void openAgent(Player p,String id){Agent a=plugin.state().agent(id);if(a!=null)open(p,new Menu(Kind.AGENT,id,27,a.name+" · 村民档案"));}
    public void openHistory(Player p){open(p,new Menu(Kind.HISTORY,"",54,"共享麦田 · 最近回合"));}
    private void open(Player p,Menu m){fill(m);p.openInventory(m.inventory);}
    public void refreshOpen(){for(Player p:Bukkit.getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu m)fill(m);}
    private void put(Menu m,int slot,Material material,String title,NamedTextColor color,String... lines) {
        ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();
        meta.displayName(Component.text(title,color).decoration(TextDecoration.ITALIC,false));
        meta.lore(Arrays.stream(lines).map(s->Component.text(s,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)).toList());
        item.setItemMeta(meta);m.inventory.setItem(slot,item);
    }
    private void fill(Menu m) {
        m.inventory.clear();for(int i=0;i<m.inventory.getSize();i++)if(i<9||i>=m.inventory.getSize()-9||i%9==0||i%9==8)put(m,i,Material.BROWN_STAINED_GLASS_PANE," ",NamedTextColor.GRAY);
        WorldState s=plugin.state();
        switch(m.kind) {
            case FIELD->{
                put(m,4,Material.SUNFLOWER,s.profileName()+" · 第"+s.round+"回合",NamedTextColor.GOLD,
                    "成熟小麦 "+s.mature()+"/48 · "+s.lastMode,"本轮收割 "+s.lastHarvest+" / 恢复 "+s.lastRegrowth,
                    "个人储粮 "+s.stocks()+" / 缺粮 "+s.lastShortages+"人",s.headline);
                put(m,10,Material.EMERALD,"普通AI实验",NamedTextColor.GREEN,
                    "重置为48株麦田，每人1份口粮","性格兼顾个人口粮和长期粮源","四人自主选择收割量，消耗API","连续点击两次，5秒内确认");
                put(m,12,Material.DIAMOND_HOE,"激进AI实验",NamedTextColor.RED,
                    "与普通组使用相同初始资源及恢复规则","性格更重视私人储粮、当期收益","四人自主选择收割量，消耗API","连续点击两次，5秒内确认");
                put(m,14,plugin.busy()?Material.HONEYCOMB:Material.CLOCK,plugin.busy()?"本回合进行中…":"下一回合",NamedTextColor.GREEN,
                    "AI决策 → 收割 → 恢复 → 口粮消耗","四名村民合并为一次模型请求",plugin.notice());
                put(m,16,plugin.automatic()?Material.RED_DYE:Material.LIME_DYE,plugin.automatic()?"暂停自动回合":"开启自动回合",NamedTextColor.AQUA,
                    "上一回合完成后再计时，不并发堆积","村内无人时自动暂停");
                put(m,19,Material.WHEAT,"共享麦田 "+s.mature()+"/48",NamedTextColor.YELLOW,
                    "7×7，中央水源，48格耕地","收割后剩R株，恢复floor(R/4)株","最多48株，剩余越少恢复越慢");
                put(m,21,Material.HAY_BLOCK,"个人储粮合计 "+s.stocks()+"份",NamedTextColor.YELLOW,
                    "每人每回合消耗1份","本轮缺粮 "+s.lastShortages+"人","右键各村民查看个人库存和理由");
                put(m,23,Material.PAPER,"最近回合",NamedTextColor.WHITE,
                    "查看麦田变化、个人库存、缺粮和来源","完整历史同时保存在本地");
                var api=plugin.apiStatus();
                put(m,25,Material.COPPER_INGOT,"API运行记录",NamedTextColor.AQUA,
                    "本次启动请求 "+api.requests()+"/"+api.requestLimit(),"结构解析回合 "+api.successfulRounds(),
                    "参数 "+api.parameters()+" / 已接受 "+api.parametersAccepted(),
                    "累计tokens "+(api.promptTokens()+api.completionTokens()),"API异常或请求上限时暂停，不改变资源");
                int interval=Math.clamp(plugin.getConfig().getInt("auto-interval-seconds",5),5,300);
                put(m,28,Material.REPEATER,"自动回合间隔 "+interval+"秒",NamedTextColor.WHITE,
                    "点击轮换5 / 10 / 20秒","从上一回合完成时开始等待");
                put(m,30,Material.BOOK,"四名村民",NamedTextColor.YELLOW,
                    "右键村民查看收割理由、意图和实收","最近五回合记忆会影响后续选择");
                put(m,32,Material.REDSTONE_TORCH,"重载API配置",NamedTextColor.AQUA,
                    "修改本地API配置后点击重载","保留实验状态和累计请求数");
                put(m,34,Material.BARRIER,"重置当前实验",NamedTextColor.RED,
                    "保留当前普通或激进性格","恢复满田、每人1份口粮，暂停自动","连续点击两次，5秒内确认；旧日志保留");
                put(m,40,Material.COMPASS,"回到麦田",NamedTextColor.WHITE,"双击空格可飞行观察");
            }
            case AGENT->{
                Agent a=s.agent(m.id);if(a==null)return;
                put(m,4,Material.NAME_TAG,a.name+" · "+s.profileName(),NamedTextColor.GOLD,a.personality);
                put(m,10,Material.HAY_BLOCK,"个人库存 "+a.wheat+"份",NamedTextColor.YELLOW,
                    "每回合口粮需求1份","可维持约"+a.wheat+"回合",
                    "上回合缺粮 "+a.unmet+" / 连续缺粮 "+a.hungerRounds);
                put(m,12,Material.IRON_HOE,"意图"+a.lastRequested+" / 实收"+a.lastHarvest,NamedTextColor.GREEN,
                    a.lastReason,"累计收割 "+a.totalHarvest+"株","最近决策来源："+a.decisionSource);
                put(m,14,Material.WHEAT,"麦田 "+s.mature()+"/48",NamedTextColor.YELLOW,
                    "本轮共收割 "+s.lastHarvest+" / 恢复 "+s.lastRegrowth);
                List<String> lines=new ArrayList<>(a.memory);if(lines.isEmpty())lines.add("还没有回合记忆");
                put(m,16,Material.BOOK,"最近五回合记忆",NamedTextColor.WHITE,lines.toArray(String[]::new));
                put(m,22,Material.ARROW,"返回实验面板",NamedTextColor.WHITE);
            }
            case HISTORY->{
                int i=0;
                for(var r:plugin.history()) {
                    WorldState v=r.state();int slot=10+(i/7)*9+i%7;i++;
                    put(m,slot,r.source().equals("AI")?Material.EMERALD:Material.PAPER,v.profileName()+" · 第"+v.round+"回合",NamedTextColor.AQUA,
                        "来源 "+r.source(),"麦田 "+v.lastBefore+"→"+v.mature()+" / 收割 "+v.lastHarvest+" / 恢复 "+v.lastRegrowth,
                        "个人储粮 "+v.stocks()+" / 缺粮 "+v.lastShortages,
                        "API请求 "+r.apiRequests()+" / 耗时 "+r.elapsedMs()+"ms",v.headline);
                }
                if(i==0)put(m,22,Material.PAPER,"本次启动尚无回合记录",NamedTextColor.GRAY,"旧日志仍保存在本地");
                put(m,49,Material.ARROW,"返回实验面板",NamedTextColor.WHITE);
            }
        }
    }
    private boolean confirm(Player p,String action) {
        long now=System.currentTimeMillis();
        if(action.equals(confirm.get(p.getUniqueId()))&&now-confirmedAt.getOrDefault(p.getUniqueId(),0L)<=5000) {
            confirm.remove(p.getUniqueId());confirmedAt.remove(p.getUniqueId());return true;
        }
        confirm.put(p.getUniqueId(),action);confirmedAt.put(p.getUniqueId(),now);
        plugin.say(p,"5秒内再点击同一个按钮确认重置");return false;
    }
    @EventHandler public void click(InventoryClickEvent e) {
        if(!(e.getView().getTopInventory().getHolder() instanceof Menu m))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getRawSlot()<0||e.getRawSlot()>=m.inventory.getSize())return;
        int slot=e.getRawSlot();
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!p.isOnline())return;
            if(m.kind==Kind.AGENT){if(slot==22)openMayor(p);return;}
            if(m.kind==Kind.HISTORY){if(slot==49)openMayor(p);return;}
            switch(slot) {
                case 10->{if(confirm(p,"ai"))plugin.scenario(p,"ai");}
                case 12->{if(confirm(p,"ai-greedy"))plugin.scenario(p,"ai-greedy");}
                case 14->plugin.nextRound(p);
                case 16->plugin.toggleAuto(p);
                case 23->openHistory(p);
                case 28->{int n=plugin.getConfig().getInt("auto-interval-seconds",5);plugin.speed(p,n==5?10:n==10?20:5);}
                case 32->plugin.onCommand(p,plugin.getCommand("village"),"village",new String[]{"reload"});
                case 34->{if(confirm(p,"reset"))plugin.reset(p);}
                case 40->{p.closeInventory();p.teleport(plugin.arena().spawnLocation());}
                default->{}
            }
        });
    }
    @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Menu)e.setCancelled(true);}
}
