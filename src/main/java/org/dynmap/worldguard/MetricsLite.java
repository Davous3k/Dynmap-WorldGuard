package org.dynmap.worldguard;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.YamlConfigurationOptions;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

public class MetricsLite
{
  private static final int REVISION = 5;
  private static final String BASE_URL = "http://mcstats.org";
  private static final String REPORT_URL = "/report/%s";
  private static final int PING_INTERVAL = 10;
  private final Plugin plugin;
  private final YamlConfiguration configuration;
  private final File configurationFile;
  private final String guid;
  private final Object optOutLock = new Object();
  private volatile BukkitTask taskId = null;
  
  public MetricsLite(Plugin plugin)
    throws IOException
  {
    if (plugin == null) {
      throw new IllegalArgumentException("Plugin cannot be null");
    }
    this.plugin = plugin;
    
    this.configurationFile = getConfigFile();
    this.configuration = YamlConfiguration.loadConfiguration(this.configurationFile);
    
    this.configuration.addDefault("opt-out", Boolean.valueOf(false));
    this.configuration.addDefault("guid", UUID.randomUUID().toString());
    if (this.configuration.get("guid", null) == null)
    {
      this.configuration.options().header("http://mcstats.org").copyDefaults(true);
      this.configuration.save(this.configurationFile);
    }
    this.guid = this.configuration.getString("guid");
  }
  
  public boolean start()
  {
    synchronized (this.optOutLock)
    {
      if (isOptOut()) {
        return false;
      }
      if (this.taskId != null) {
        return true;
      }
      this.taskId = this.plugin.getServer().getScheduler().runTaskTimerAsynchronously(this.plugin, new Runnable()
      {
        private boolean firstPost = true;
        
        public void run()
        {
          try
          {
            synchronized (MetricsLite.this.optOutLock)
            {
              if ((MetricsLite.this.isOptOut()) && (MetricsLite.this.taskId != null))
              {
                MetricsLite.this.taskId.cancel();
                MetricsLite.this.taskId = null;
              }
            }
            MetricsLite.this.postPlugin(!this.firstPost);
            
            this.firstPost = false;
          }
          catch (IOException localIOException) {}
        }
      }, 0L, 12000L);
      
      return true;
    }
  }
  
  public boolean isOptOut()
  {
    synchronized (this.optOutLock)
    {
      try
      {
        this.configuration.load(getConfigFile());
      }
      catch (IOException ex)
      {
        Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
        return true;
      }
      catch (InvalidConfigurationException ex)
      {
        Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
        return true;
      }
      return this.configuration.getBoolean("opt-out", false);
    }
  }
  
  public void enable()
    throws IOException
  {
    synchronized (this.optOutLock)
    {
      if (isOptOut())
      {
        this.configuration.set("opt-out", Boolean.valueOf(false));
        this.configuration.save(this.configurationFile);
      }
      if (this.taskId == null) {
        start();
      }
    }
  }
  
  public void disable()
    throws IOException
  {
    synchronized (this.optOutLock)
    {
      if (!isOptOut())
      {
        this.configuration.set("opt-out", Boolean.valueOf(true));
        this.configuration.save(this.configurationFile);
      }
      if (this.taskId != null)
      {
        this.taskId.cancel();
        this.taskId = null;
      }
    }
  }
  
  public File getConfigFile()
  {
    File pluginsFolder = this.plugin.getDataFolder().getParentFile();
    
    return new File(new File(pluginsFolder, "PluginMetrics"), "config.yml");
  }
  
  private void postPlugin(boolean isPing)
    throws IOException
  {
    PluginDescriptionFile description = this.plugin.getDescription();
    
    StringBuilder data = new StringBuilder();
    data.append(encode("guid")).append('=').append(encode(this.guid));
    encodeDataPair(data, "version", description.getVersion());
    encodeDataPair(data, "server", Bukkit.getVersion());
    encodeDataPair(data, "players", Integer.toString(Bukkit.getServer().getOnlinePlayers().size()));
    encodeDataPair(data, "revision", String.valueOf(5));
    if (isPing) {
      encodeDataPair(data, "ping", "true");
    }
    URL url = new URL("http://mcstats.org" + String.format("/report/%s", new Object[] { encode(this.plugin.getDescription().getName()) }));
    URLConnection connection;
    URLConnection connection1;
    if (isMineshafterPresent()) {
      connection1 = url.openConnection(Proxy.NO_PROXY);
    } else {
      connection1 = url.openConnection();
    }
    connection1.setDoOutput(true);
    
    OutputStreamWriter writer = new OutputStreamWriter(connection1.getOutputStream());
    writer.write(data.toString());
    writer.flush();
    
    BufferedReader reader = new BufferedReader(new InputStreamReader(connection1.getInputStream()));
    String response = reader.readLine();
    
    writer.close();
    reader.close();
    if ((response == null) || (response.startsWith("ERR"))) {
      throw new IOException(response);
    }
  }
  
  private boolean isMineshafterPresent()
  {
    try
    {
      Class.forName("mineshafter.MineServer");
      return true;
    }
    catch (Exception e) {}
    return false;
  }
  
  private static void encodeDataPair(StringBuilder buffer, String key, String value)
    throws UnsupportedEncodingException
  {
    buffer.append('&').append(encode(key)).append('=').append(encode(value));
  }
  
  private static String encode(String text)
    throws UnsupportedEncodingException
  {
    return URLEncoder.encode(text, "UTF-8");
  }
}
