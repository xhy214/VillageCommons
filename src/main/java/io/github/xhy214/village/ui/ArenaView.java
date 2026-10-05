package io.github.xhy214.village.ui;

import io.github.xhy214.village.core.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

public final class ArenaView {
    public static final int Y=64;
    private final JavaPlugin plugin;
    private final NamespacedKey tag;
    private final Map<String,Villager> villagers=new LinkedHashMap<>();
    private final Map<String,Location> homes=new HashMap<>();
    private final Map<String,TextDisplay> labels=new HashMap<>();
    private final List<Location> plots=new ArrayList<>();
    private World world;private TextDisplay board,experiment;
    private BukkitTask animation;
    public ArenaView(JavaPlugin plugin) {this.plugin=plugin;tag=new NamespacedKey(plugin,"commons-object");}
    public World world(){return world;}
    public boolean inside(Location l){return world!=null && world.equals(l.getWorld());}
    public String agentId(Entity e){return e.getPersistentDataContainer().get(tag,PersistentDataType.STRING);}
    public Location spawnLocation(){return new Location(world,0.5,Y,8.5,180,12);}
    public int actualMature() {
        int count=0;
        for(Location l:plots)if(l.getBlock().getType()==Material.WHEAT &&
            l.getBlock().getBlockData() instanceof Ageable a && a.getAge()==a.getMaximumAge())count++;
        return count;
    }
    public void initialize(String name,WorldState state) {
        world=Bukkit.getWorld(name);
        if(world==null)world=new WorldCreator(name).generator(new FlatVillageGenerator()).generateStructures(false).seed(214).createWorld();
        if(world==null)throw new IllegalStateException("Cannot create commons world");
        world.setDifficulty(Difficulty.PEACEFUL);world.setTime(6000);world.setStorm(false);
        world.setGameRule(GameRules.ADVANCE_TIME,false);world.setGameRule(GameRules.ADVANCE_WEATHER,false);
        world.setGameRule(GameRules.SPAWN_MOBS,false);world.setGameRule(GameRules.RANDOM_TICK_SPEED,0);
        world.setGameRule(GameRules.MOB_GRIEFING,false);world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER,0);
        world.setSpawnLocation(0,Y,8,180);
        world.getWorldBorder().setCenter(0,0);world.getWorldBorder().setSize(50);
        for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++)world.addPluginChunkTicket(x,z,plugin);
        for(Entity e:world.getEntities())if(e.getPersistentDataContainer().has(tag,PersistentDataType.STRING))e.remove();
        build();spawn(state);refresh(state,false,"");
    }
    private void block(int x,int y,int z,Material m){world.getBlockAt(x,y,z).setType(m,false);}
    private void region(int x1,int x2,int y1,int y2,int z1,int z2,Material m) {
        for(int x=x1;x<=x2;x++)for(int y=y1;y<=y2;y++)for(int z=z1;z<=z2;z++)block(x,y,z,m);
    }
    private void build() {
        region(-19,19,Y,Y+9,-19,19,Material.AIR);region(-19,19,Y-1,Y-1,-19,19,Material.GRASS_BLOCK);
        region(-2,2,Y-1,Y-1,-18,18,Material.DIRT_PATH);
        region(-18,18,Y-1,Y-1,-2,2,Material.DIRT_PATH);
        // 7x7 crops + centre water; a 9x9 path ring and an 11x11 boundary.
        region(-4,4,Y-1,Y-1,-4,4,Material.SMOOTH_STONE);
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
            if(x==0 && z==0) {
                block(x,Y-1,z,Material.WATER);block(x,Y,z,Material.LILY_PAD);continue;
            }
            Farmland data=(Farmland)Material.FARMLAND.createBlockData();data.setMoisture(data.getMaximumMoisture());
            world.getBlockAt(x,Y-1,z).setBlockData(data,false);plots.add(new Location(world,x,Y,z));
        }
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)if(Math.max(Math.abs(x),Math.abs(z))==5) {
            if((x==0 && Math.abs(z)==5)||(z==0 && Math.abs(x)==5))continue;
            block(x,Y,z,Material.OAK_FENCE);
        }
        for(int x:new int[]{-5,5})for(int z:new int[]{-5,5}) {
            block(x,Y+1,z,Material.OAK_FENCE);block(x,Y+2,z,Material.LANTERN);
        }
        cottage(-11,-10,Material.SPRUCE_PLANKS);cottage(11,-10,Material.OAK_PLANKS);
        cottage(-11,10,Material.OAK_PLANKS);cottage(11,10,Material.SPRUCE_PLANKS);
        region(-3,3,Y-1,Y-1,11,15,Material.STONE_BRICKS);
        for(int x:new int[]{-3,3}) {
            region(x,x,Y,Y+2,13,13,Material.OAK_FENCE);block(x,Y+3,13,Material.LANTERN);
        }
        block(0,Y,14,Material.BELL);
        board=text(new Location(world,0.5,Y+3.4,-6.5),"共享麦田",NamedTextColor.GOLD,"field-board");board.setLineWidth(270);
        experiment=text(new Location(world,0.5,Y+3,12.5),"AI收割实验",NamedTextColor.GOLD,"experiment-board");experiment.setLineWidth(280);
        for(int x:new int[]{-17,17})for(int z:new int[]{-17,17}) {
            region(x,x,Y,Y+3,z,z,Material.OAK_LOG);region(x-1,x+1,Y+3,Y+4,z-1,z+1,Material.OAK_LEAVES);
        }
    }
    private void cottage(int x,int z,Material wall) {
        region(x-3,x+3,Y-1,Y-1,z-2,z+2,Material.COBBLESTONE);
        region(x-3,x+3,Y,Y+2,z-2,z+2,wall);
        region(x-2,x+2,Y,Y+2,z-1,z+1,Material.AIR);
        block(x,Y,z-2,Material.AIR);block(x,Y+1,z-2,Material.AIR);
        for(int dx:new int[]{-2,2}){block(x+dx,Y+1,z-2,Material.GLASS_PANE);block(x+dx,Y+1,z+2,Material.GLASS_PANE);}
        for(int step=0;step<4;step++)for(int dx=-4;dx<=4;dx++) {
            stair(x+dx,Y+3+step,z-3+step,BlockFace.SOUTH);
            if(step<3)stair(x+dx,Y+3+step,z+3-step,BlockFace.NORTH);
        }
        block(x,Y+2,z-2,Material.LANTERN);block(x-2,Y,z+1,Material.BARREL);
        block(x+2,Y,z+1,Material.HAY_BLOCK);
    }
    private void stair(int x,int y,int z,BlockFace facing) {
        Directional d=(Directional)Material.DARK_OAK_STAIRS.createBlockData();d.setFacing(facing);world.getBlockAt(x,y,z).setBlockData(d,false);
    }
    private TextDisplay text(Location l,String value,NamedTextColor color,String id) {
        return world.spawn(l,TextDisplay.class,e->{
            e.text(Component.text(value,color));e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(Color.fromARGB(155,29,33,26));e.setShadowed(true);e.setPersistent(false);
            e.setLineWidth(180);e.setViewRange(0.75f);e.getPersistentDataContainer().set(tag,PersistentDataType.STRING,id);
        });
    }
    private void spawn(WorldState s) {
        double[][] coords={{-3.5,-1.5},{1.5,-3.5},{4.5,1.5},{-1.5,4.5}};
        for(int i=0;i<4;i++) {
            Agent a=s.agents.get(i);Location l=new Location(world,coords[i][0],Y,coords[i][1]);
            Villager v=world.spawn(l,Villager.class,e->{
                e.setAI(false);e.setInvulnerable(true);e.setSilent(true);e.setCollidable(false);e.setPersistent(false);
                e.setProfession(Villager.Profession.FARMER);e.setVillagerType(Villager.Type.PLAINS);e.setAdult();
                e.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_HOE));
                e.getPersistentDataContainer().set(tag,PersistentDataType.STRING,a.id);
            });
            villagers.put(a.id,v);homes.put(a.id,l);
            labels.put(a.id,text(l.clone().add(0,2.35,0),a.name,NamedTextColor.GREEN,a.id+"-label"));
        }
    }
    private void crop(int index,int age) {
        Location l=plots.get(index);
        if(age<0){l.getBlock().setType(Material.AIR,false);return;}
        Ageable data=(Ageable)Material.WHEAT.createBlockData();data.setAge(age);l.getBlock().setBlockData(data,false);
    }
    public void refresh(WorldState s,boolean busy,String notice) {
        if(animation==null)for(int i=0;i<48;i++)crop(i,s.ripe[i]?7:-1);
        String mode=s.profileName();
        board.text(Component.text("麦风村 · 共享麦田 7×7\n",NamedTextColor.GOLD)
            .append(Component.text("第 "+s.round+" 回合 · "+mode+" · "+s.lastMode+"\n",NamedTextColor.WHITE))
            .append(Component.text("成熟 "+s.mature()+"/48    收割 "+s.lastHarvest+"    恢复 "+s.lastRegrowth+"\n",s.mature()<20?NamedTextColor.RED:NamedTextColor.GREEN))
            .append(Component.text("个人库存合计 "+s.stocks()+"    缺粮 "+s.lastShortages+" 人\n",NamedTextColor.WHITE))
            .append(Component.text(busy||notice.startsWith("本回合未结算")?notice:"/village 开始下一回合 · 右键查看村民",NamedTextColor.AQUA)));
        experiment.text(Component.text("当前实验 · "+mode+"\n",NamedTextColor.GOLD)
            .append(Component.text(s.aiProfile==WorldState.AiProfile.GREEDY?"偏重私人储粮与眼前收益\n":"兼顾个人口粮与未来粮源\n",NamedTextColor.WHITE))
            .append(Component.text("每人自主收割0～3株 · 每轮消耗1份\n",NamedTextColor.AQUA))
            .append(Component.text("收割后剩R株，恢复floor(R/4)，最多48株\n",NamedTextColor.GRAY))
            .append(Component.text(s.headline,NamedTextColor.WHITE)));
        for(Agent a:s.agents) {
            TextDisplay label=labels.get(a.id);
            label.text(Component.text(a.name+" · "+a.wheat+"份储粮\n",a.unmet>0?NamedTextColor.RED:NamedTextColor.GREEN)
                .append(Component.text("意图"+a.lastRequested+" / 实收"+a.lastHarvest+" · "+a.decisionSource,NamedTextColor.WHITE)));
        }
    }
    public void animate(CommonsEngine.Outcome out,Runnable completed) {
        cancelAnimation();
        if(!plugin.getConfig().getBoolean("animations-enabled",true)){completed.run();return;}
        Map<String,Deque<Integer>> jobs=new LinkedHashMap<>();
        for(String id:villagers.keySet())jobs.put(id,new ArrayDeque<>());
        for(var h:out.harvests())jobs.get(h.id()).add(h.plot());
        Map<String,Integer> pauses=new HashMap<>();int[] elapsed={0},recoveryFrame={0};
        animation=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            elapsed[0]++;boolean allDone=true;
            for(var entry:jobs.entrySet()) {
                String id=entry.getKey();Villager v=villagers.get(id);Deque<Integer> queue=entry.getValue();
                if(queue.isEmpty())continue;allDone=false;
                int pause=pauses.getOrDefault(id,0);if(pause>0){pauses.put(id,pause-1);continue;}
                int index=queue.peekFirst();Location target=plots.get(index).clone().add(0.5,0,0.5),l=v.getLocation();
                double dx=target.getX()-l.getX(),dz=target.getZ()-l.getZ(),distance=Math.hypot(dx,dz);
                if(distance>0.3) {
                    double step=Math.min(0.6,distance);l.add(dx/distance*step,0,dz/distance*step);
                    l.setYaw((float)Math.toDegrees(Math.atan2(-dx,dz)));v.teleport(l);
                }else {
                    var harvestedData=plots.get(index).getBlock().getBlockData();
                    v.swingMainHand();crop(index,-1);
                    world.spawnParticle(Particle.BLOCK,target.clone().add(0,0.4,0),9,0.25,0.25,0.25,harvestedData);
                    world.playSound(target,Sound.BLOCK_CROP_BREAK,0.45f,1.0f);queue.removeFirst();pauses.put(id,2);
                }
                labels.get(id).teleport(v.getLocation().add(0,2.35,0));
            }
            if(!allDone && elapsed[0]<160)return;
            // Bound animation duration; final state always comes from validated simulation.
            if(!allDone){for(var h:out.harvests())crop(h.plot(),-1);for(var q:jobs.values())q.clear();}
            recoveryFrame[0]++;
            for(int index:out.regrown())crop(index,Math.min(7,recoveryFrame[0]));
            if(recoveryFrame[0]<7)return;
            cancelAnimation();resetPositions();
            for(int index:out.regrown())world.spawnParticle(Particle.HAPPY_VILLAGER,plots.get(index).clone().add(0.5,0.6,0.5),2,0.1,0.1,0.1,0);
            completed.run();
            for(Player p:world.getPlayers())p.playSound(p.getLocation(),Sound.BLOCK_BELL_USE,0.25f,1.2f);
        },1,4);
    }
    public void cancelAnimation(){if(animation!=null){animation.cancel();animation=null;}}
    public void resetPositions() {
        cancelAnimation();
        for(String id:villagers.keySet()){villagers.get(id).teleport(homes.get(id));labels.get(id).teleport(homes.get(id).clone().add(0,2.35,0));}
    }
    public void stop() {
        cancelAnimation();
        if(world!=null){for(Entity e:world.getEntities())if(e.getPersistentDataContainer().has(tag,PersistentDataType.STRING))e.remove();world.removePluginChunkTickets(plugin);}
    }
}
