package org.dynmap.worldguard;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.domains.PlayerDomain;
import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;





public class DynmapWorldGuardPlugin
  extends JavaPlugin
{
  private static Logger log;
  private static final String DEF_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> Owner <span style=\"font-weight:bold;\">%playerowners%</span><br />Flags<br /><span style=\"font-weight:bold;\">%flags%</span></div>";
  public static final String BOOST_FLAG = "dynmap-boost";
  Plugin dynmap;
  DynmapAPI api;
  MarkerAPI markerapi;
  BooleanFlag boost_flag;
  int updatesPerTick = 20;
  
  FileConfiguration cfg;
  MarkerSet set;
  long updperiod;
  boolean use3d;
  String infowindow;
  AreaStyle defstyle;
  Map<String, AreaStyle> cusstyle;
  Map<String, AreaStyle> cuswildstyle;
  Map<String, AreaStyle> ownerstyle;
  Set<String> visible;
  Set<String> hidden;
  boolean stop;
  int maxdepth;
  
  public void onLoad() {
    log = getLogger();
    registerCustomFlags();
  }

  
  private static class AreaStyle
  {
    String strokecolor;
    String unownedstrokecolor;
    double strokeopacity;
    int strokeweight;
    String fillcolor;
    double fillopacity;
    String label;
    
    AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
      this.strokecolor = cfg.getString(String.valueOf(path) + ".strokeColor", def.strokecolor);
      this.unownedstrokecolor = cfg.getString(String.valueOf(path) + ".unownedStrokeColor", def.unownedstrokecolor);
      this.strokeopacity = cfg.getDouble(String.valueOf(path) + ".strokeOpacity", def.strokeopacity);
      this.strokeweight = cfg.getInt(String.valueOf(path) + ".strokeWeight", def.strokeweight);
      this.fillcolor = cfg.getString(String.valueOf(path) + ".fillColor", def.fillcolor);
      this.fillopacity = cfg.getDouble(String.valueOf(path) + ".fillOpacity", def.fillopacity);
      this.label = cfg.getString(String.valueOf(path) + ".label", null);
    }

    
    AreaStyle(FileConfiguration cfg, String path) {
      this.strokecolor = cfg.getString(String.valueOf(path) + ".strokeColor", "#FF0000");
      this.unownedstrokecolor = cfg.getString(String.valueOf(path) + ".unownedStrokeColor", "#00FF00");
      this.strokeopacity = cfg.getDouble(String.valueOf(path) + ".strokeOpacity", 0.8D);
      this.strokeweight = cfg.getInt(String.valueOf(path) + ".strokeWeight", 3);
      this.fillcolor = cfg.getString(String.valueOf(path) + ".fillColor", "#FF0000");
      this.fillopacity = cfg.getDouble(String.valueOf(path) + ".fillOpacity", 0.35D);
    }
  }


  
  public static void info(String msg) { log.log(Level.INFO, msg); }



  
  public static void severe(String msg) { log.log(Level.SEVERE, msg); }

  
  private Map<String, AreaMarker> resareas = new HashMap();

  
  private String formatInfoWindow(ProtectedRegion region, AreaMarker m) {
    String v = "<div class=\"regioninfo\">" + this.infowindow + "</div>";
    v = v.replace("%regionname%", m.getLabel());
    v = v.replace("%playerowners%", region.getOwners().toPlayersString(WorldGuard.getInstance().getProfileCache()));
    v = v.replace("%groupowners%", region.getOwners().toGroupsString());
    v = v.replace("%playermembers%", region.getMembers().toPlayersString(WorldGuard.getInstance().getProfileCache()));
    v = v.replace("%groupmembers%", region.getMembers().toGroupsString());
    if (region.getParent() != null) {
      v = v.replace("%parent%", region.getParent().getId());
    } else {
      v = v.replace("%parent%", "");
    } 
    v = v.replace("%priority%", String.valueOf(region.getPriority()));
    Map<Flag<?>, Object> map = region.getFlags();
    String flgs = "";
    for (Flag<?> f : map.keySet()) {
      flgs = String.valueOf(flgs) + f.getName() + ": " + map.get(f).toString() + "<br/>";
    }
    return v.replace("%flags%", flgs);
  }


  
  private boolean isVisible(String id, String worldname) {
    if (this.visible != null && this.visible.size() > 0 && 
      !this.visible.contains(id) && !this.visible.contains("world:" + worldname) && 
      !this.visible.contains(String.valueOf(worldname) + "/" + id)) {
      return false;
    }
    if (this.hidden != null && this.hidden.size() > 0 && (
      this.hidden.contains(id) || this.hidden.contains("world:" + worldname) || this.hidden.contains(String.valueOf(worldname) + "/" + id))) {
      return false;
    }
    return true;
  }

  
  private void addStyle(String resid, String worldid, AreaMarker m, ProtectedRegion region) {
    AreaStyle as = (AreaStyle)this.cusstyle.get(String.valueOf(worldid) + "/" + resid);
    if (as == null) {
      as = (AreaStyle)this.cusstyle.get(resid);
    }
    if (as == null) {
      for (String wc : this.cuswildstyle.keySet()) {
        
        String[] tok = wc.split("\\|");
        if (tok.length == 1 && resid.startsWith(tok[0])) {
          as = (AreaStyle)this.cuswildstyle.get(wc); continue;
        }  if (tok.length >= 2 && resid.startsWith(tok[0]) && resid.endsWith(tok[1])) {
          as = (AreaStyle)this.cuswildstyle.get(wc);
        }
      } 
    }

    
    if (as == null && 
      !this.ownerstyle.isEmpty()) {
      
      DefaultDomain dd = region.getOwners();
      PlayerDomain pd = dd.getPlayerDomain();
      if (pd != null) {
        
        for (String p1 : pd.getPlayers()) {
          if (as == null) {
            
            as = (AreaStyle)this.ownerstyle.get(p1.toLowerCase());
            if (as != null) {
              break;
            }
          } 
        } 
        if (as == null) {
          for (UUID uuid : pd.getUniqueIds()) {
            
            as = (AreaStyle)this.ownerstyle.get(uuid.toString());
            if (as != null) {
              break;
            }
          } 
        }
        if (as == null) {
          for (Iterator<String> tok = pd.getPlayers().iterator(); tok.hasNext(); ) {
            
            String p = (String)tok.next();
            if (p != null) {
              
              as = (AreaStyle)this.ownerstyle.get(p.toLowerCase());
              if (as != null) {
                break;
              }
            } 
          } 
        }
      } 
      if (as == null) {
        
        Set<String> grp = dd.getGroups();
        if (grp != null) {
          for (String p1 : grp) {
            
            as = (AreaStyle)this.ownerstyle.get(p1.toLowerCase());
            if (as != null) {
              break;
            }
          } 
        }
      } 
    } 
    if (as == null) {
      as = this.defstyle;
    }
    boolean unowned = false;
    if (region.getOwners().getPlayers().size() == 0 && 
      region.getOwners().getUniqueIds().size() == 0 && 
      region.getOwners().getGroups().size() == 0) {
      unowned = true;
    }
    int sc = 16711680;
    int fc = 16711680;
    
    try {
      if (unowned) {
        sc = Integer.parseInt(as.unownedstrokecolor.substring(1), 16);
      } else {
        sc = Integer.parseInt(as.strokecolor.substring(1), 16);
      } 
      fc = Integer.parseInt(as.fillcolor.substring(1), 16);
    }
    catch (NumberFormatException numberFormatException) {}
    m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
    m.setFillStyle(as.fillopacity, fc);
    if (as.label != null) {
      m.setLabel(as.label);
    }
    if (this.boost_flag != null) {
      
      Boolean b = (Boolean)region.getFlag(this.boost_flag);
      m.setBoostFlag((b == null) ? false : b.booleanValue());
    } 
  }

  
  private void handleRegion(World world, ProtectedRegion region, Map<String, AreaMarker> newmap) {
    String name = region.getId();
    
    name = String.valueOf(name.substring(0, 1).toUpperCase()) + name.substring(1);
    double[] x = null;
    double[] z = null;
    if (isVisible(region.getId(), world.getName())) {
      
      String id = region.getId();
      RegionType tn = region.getType();
      BlockVector3 l0 = region.getMinimumPoint();
      BlockVector3 l1 = region.getMaximumPoint();
      if (tn == RegionType.CUBOID) {
        
        x = new double[4];
        z = new double[4];
        x[0] = l0.getX(); z[0] = l0.getZ();
        x[1] = l0.getX(); z[1] = l1.getZ() + 1.0D;
        x[2] = l1.getX() + 1.0D; z[2] = l1.getZ() + 1.0D;
        x[3] = l1.getX() + 1.0D; z[3] = l0.getZ();
      }
      else if (tn == RegionType.POLYGON) {
        
        ProtectedPolygonalRegion ppr = (ProtectedPolygonalRegion)region;
        List<BlockVector2> points = ppr.getPoints();
        x = new double[points.size()];
        z = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
          
          BlockVector2 pt = (BlockVector2)points.get(i);
          x[i] = pt.getX(); z[i] = pt.getZ();
        } 
      } else {
        return;
      } 

      
      String markerid = String.valueOf(world.getName()) + "_" + id;
      AreaMarker m = (AreaMarker)this.resareas.remove(markerid);
      if (m == null) {
        
        m = this.set.createAreaMarker(markerid, name, false, world.getName(), x, z, false);
      
      }
      else {
        
        m.setCornerLocations(x, z);
        m.setLabel(name);
      } 
      if (this.use3d) {
        m.setRangeY(l1.getY() + 1.0D, l0.getY());
      }
      addStyle(id, world.getName(), m, region);
      
      String desc = formatInfoWindow(region, m);
      
      m.setDescription(desc);
      
      newmap.put(markerid, m);
    } 
  }
  
  private class UpdateJob
    implements Runnable
  {
    Map<String, AreaMarker> newmap = new HashMap();
    List<World> worldsToDo = null;
    List<ProtectedRegion> regionsToDo = null;
    World curworld = null;



    
    public void run() {
      if (DynmapWorldGuardPlugin.this.stop) {
        return;
      }
      
      if (this.worldsToDo == null) {
        
        List<org.bukkit.World> w = Bukkit.getWorlds();
        this.worldsToDo = new ArrayList();
        for (org.bukkit.World wrld : w) {
          this.worldsToDo.add(WorldGuard.getInstance().getPlatform().getMatcher().getWorldByName(wrld.getName()));
        }
      } 
      while (this.regionsToDo == null) {
        
        if (this.worldsToDo.isEmpty()) {
          
          for (AreaMarker oldm : DynmapWorldGuardPlugin.this.resareas.values()) {
            oldm.deleteMarker();
          }
          DynmapWorldGuardPlugin.this.resareas = this.newmap;
          
          DynmapWorldGuardPlugin.this.getServer().getScheduler().scheduleSyncDelayedTask(DynmapWorldGuardPlugin.this, new UpdateJob(DynmapWorldGuardPlugin.this, DynmapWorldGuardPlugin.this), DynmapWorldGuardPlugin.this.updperiod);
          return;
        } 
        this.curworld = (World)this.worldsToDo.remove(0);
        RegionContainer rc = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager rm = rc.get(this.curworld);
        if (rm != null) {
          
          Map<String, ProtectedRegion> regions = rm.getRegions();
          if (regions != null && !regions.isEmpty()) {
            this.regionsToDo = new ArrayList(regions.values());
          }
        } 
      } 
      for (int i = 0; i < DynmapWorldGuardPlugin.this.updatesPerTick; i++) {
        
        if (this.regionsToDo.isEmpty()) {
          
          this.regionsToDo = null;
          break;
        } 
        ProtectedRegion pr = (ProtectedRegion)this.regionsToDo.remove(this.regionsToDo.size() - 1);
        int depth = 1;
        ProtectedRegion p = pr;
        while (p.getParent() != null) {
          
          depth++;
          p = p.getParent();
        } 
        if (depth <= DynmapWorldGuardPlugin.this.maxdepth) {
          DynmapWorldGuardPlugin.this.handleRegion(this.curworld, pr, this.newmap);
        }
      } 
      DynmapWorldGuardPlugin.this.getServer().getScheduler().scheduleSyncDelayedTask(DynmapWorldGuardPlugin.this, this, 1L);
    }
    
    private UpdateJob(DynmapWorldGuardPlugin dynmapWorldGuardPlugin, DynmapWorldGuardPlugin dynmapWorldGuardPlugin2) {}
  }
  
  private class OurServerListener
    implements Listener {
    private OurServerListener(Object object, Object object2) {}
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
      Plugin p = event.getPlugin();
      String name = p.getDescription().getName();
      if (name.equals("dynmap")) {
        
        Plugin wg = p.getServer().getPluginManager().getPlugin("WorldGuard");
        if (wg != null && wg.isEnabled()) {
          DynmapWorldGuardPlugin.this.activate();
        }
      }
      else if (name.equals("WorldGuard") && DynmapWorldGuardPlugin.this.dynmap.isEnabled()) {
        
        DynmapWorldGuardPlugin.this.activate();
      } 
    }
  }

  
  public void onEnable() {
    info("initializing");
    PluginManager pm = getServer().getPluginManager();
    
    this.dynmap = pm.getPlugin("dynmap");
    if (this.dynmap == null) {
      
      severe("Cannot find dynmap!");
      return;
    } 
    this.api = (DynmapAPI)this.dynmap;
    
    Plugin wgp = pm.getPlugin("WorldGuard");
    if (wgp == null) {
      
      severe("Cannot find WorldGuard!");
      return;
    } 
    getServer().getPluginManager().registerEvents(new OurServerListener(null, null), this);
    if (this.dynmap.isEnabled() && wgp.isEnabled()) {
      activate();
    }
    
    try {
      MetricsLite ml = new MetricsLite(this);
      ml.start();
    }
    catch (IOException iOException) {}
  }


  
  private void registerCustomFlags() {
    try {
      BooleanFlag bf = new BooleanFlag("dynmap-boost");
      FlagRegistry fr = WorldGuard.getInstance().getFlagRegistry();
      fr.register(bf);
      this.boost_flag = bf;
    }
    catch (Exception x) {
      
      log.info("Error registering flag - " + x.getMessage());
    } 
    if (this.boost_flag == null) {
      log.info("Custom flag 'dynmap-boost' not registered");
    }
  }

  
  private boolean reload = false;
  
  private void activate() {
    this.markerapi = this.api.getMarkerAPI();
    if (this.markerapi == null) {
      
      severe("Error loading dynmap marker API!");
      return;
    } 
    if (this.reload) {
      reloadConfig();
    } else {
      this.reload = true;
    } 
    FileConfiguration cfg = getConfig();
    cfg.options().copyDefaults(true);
    saveConfig();
    
    this.set = this.markerapi.getMarkerSet("worldguard.markerset");
    if (this.set == null) {
      this.set = this.markerapi.createMarkerSet("worldguard.markerset", cfg.getString("layer.name", "WorldGuard"), null, false);
    } else {
      this.set.setMarkerSetLabel(cfg.getString("layer.name", "WorldGuard"));
    } 
    if (this.set == null) {
      
      severe("Error creating marker set");
      return;
    } 
    int minzoom = cfg.getInt("layer.minzoom", 0);
    if (minzoom > 0) {
      this.set.setMinZoom(minzoom);
    }
    this.set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
    this.set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
    this.use3d = cfg.getBoolean("use3dregions", false);
    this.infowindow = cfg.getString("infowindow", "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> Owner <span style=\"font-weight:bold;\">%playerowners%</span><br />Flags<br /><span style=\"font-weight:bold;\">%flags%</span></div>");
    this.maxdepth = cfg.getInt("maxdepth", 16);
    this.updatesPerTick = cfg.getInt("updates-per-tick", 20);
    
    this.defstyle = new AreaStyle(cfg, "regionstyle");
    this.cusstyle = new HashMap();
    this.ownerstyle = new HashMap();
    this.cuswildstyle = new HashMap();
    ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
    if (sect != null) {
      
      Set<String> ids = sect.getKeys(false);
      for (String id : ids) {
        if (id.indexOf('|') >= 0) {
          this.cuswildstyle.put(id, new AreaStyle(cfg, "custstyle." + id, this.defstyle)); continue;
        } 
        this.cusstyle.put(id, new AreaStyle(cfg, "custstyle." + id, this.defstyle));
      } 
    } 
    
    sect = cfg.getConfigurationSection("ownerstyle");
    if (sect != null) {
      
      Set<String> ids = sect.getKeys(false);
      for (String id : ids) {
        this.ownerstyle.put(id.toLowerCase(), new AreaStyle(cfg, "ownerstyle." + id, this.defstyle));
      }
    } 
    List<String> vis = cfg.getStringList("visibleregions");
    if (vis != null) {
      this.visible = new HashSet(vis);
    }
    Object hid = cfg.getStringList("hiddenregions");
    if (hid != null) {
      this.hidden = new HashSet((Collection)hid);
    }
    int per = cfg.getInt("update.period", 300);
    if (per < 15) {
      per = 15;
    }
    this.updperiod = (per * 20);
    this.stop = false;
    
    getServer().getScheduler().scheduleSyncDelayedTask(this, new UpdateJob(null, null), 40L);
    
    info("version " + getDescription().getVersion() + " is activated");
  }

  
  public void onDisable() {
    if (this.set != null) {
      
      this.set.deleteMarkerSet();
      this.set = null;
    } 
    this.resareas.clear();
    this.stop = true;
  }
}
