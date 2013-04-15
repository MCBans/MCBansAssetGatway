package com.mcbans.firestar.mcbans.bukkitListeners;

import static com.mcbans.firestar.mcbans.I18n._;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.mcbans.firestar.mcbans.ActionLog;
import com.mcbans.firestar.mcbans.ConfigurationManager;
import com.mcbans.firestar.mcbans.I18n;
import com.mcbans.firestar.mcbans.MCBans;
import com.mcbans.firestar.mcbans.permission.Perms;
import com.mcbans.firestar.mcbans.request.DisconnectRequest;
import com.mcbans.firestar.mcbans.util.Util;

public class PlayerListener implements Listener {
    private final MCBans plugin;
    private final ActionLog log;
    private final ConfigurationManager config;

    public PlayerListener(final MCBans plugin) {
        this.plugin = plugin;
        this.log = plugin.getLog();
        this.config = plugin.getConfigs();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable(){
            @Override
            public void run(){
                checkPlayerTask(event.getPlayer().getName(), event.getPlayer().getAddress().getAddress().getHostAddress());
            }
        });    

        if (config.isSendJoinMessage()){
            Util.message(event.getPlayer(), ChatColor.DARK_GREEN + "Server secured by MCBans!");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        // send disconnect request
        new Thread(new DisconnectRequest(plugin, event.getPlayer().getName())).start();
        
        if (plugin.mcbStaff.contains(event.getPlayer().getName())){
            plugin.mcbStaff.remove(event.getPlayer().getName());
        }
    }
    
    /**
     * Check player data. Call this method in Asynchronously!
     * @param name
     * @param ip
     */
    private void checkPlayerTask(final String name, final String ip){
        try {
            int check = 1;
            while (plugin.apiServer == null) {
                // waiting for server select
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                check++;
                if (check > 5) {
                    // can't reach mcbans servers
                    if (config.isFailsafe()){
                        log.warning("Can't reach MCBans API Servers! Kicked player: " + name);
                        kick(name, _("unavailable"));
                    }else{
                        log.warning("Can't reach MCBans API Servers! Check passed player: " + name);
                    }
                    return;
                }
            }

            // get player information
            final String uriStr = "http://" + plugin.apiServer + "/v2/" + config.getApiKey() + "/login/"
                    + URLEncoder.encode(name, "UTF-8") + "/"
                    + URLEncoder.encode(ip, "UTF-8") + "/"
                    + plugin.apiRequestSuffix;
            final URLConnection conn = new URL(uriStr).openConnection();

            conn.setConnectTimeout(config.getTimeoutInSec() * 1000);
            conn.setReadTimeout(config.getTimeoutInSec() * 1000);
            conn.setUseCaches(false);

            BufferedReader br = null;
            String response = null;
            try{
                br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                response = br.readLine();
            }finally{
                if (br != null) br.close();
            }
            if (response == null){
                if (config.isFailsafe()){
                    log.warning("Null response! Kicked player: " + name);
                    kick(name, _("unavailable"));
                }else{
                    log.warning("Null response! Check passed player: " + name);
                }
                return;
            }

            plugin.debug("Response: " + response);
            String[] s = response.split(";");
            if (s.length == 6 || s.length == 7 || s.length == 8) {
                // check banned
                if (s[0].equals("l") || s[0].equals("g") || s[0].equals("t") || s[0].equals("i") || s[0].equals("s")) {
                    if (s[0].equals("g")){ // permabanned
                        kick(name, s[1], config.autoBanPerma());
                    }
                    else if(s[0].equals("l")){ // already banned from same server
                        kick(name, s[1], true);
                    }
                    else{
                        kick(name, s[1]);
                    }
                    return;
                }
                // check reputation
                else if (config.getMinRep() > Double.valueOf(s[2])) {
                    kick(name, _("underMinRep"), config.autoBanRep());
                    return;
                }
                // check alternate accounts
                else if (config.isEnableMaxAlts() && config.getMaxAlts() < Integer.valueOf(s[3])) {
                    kick(name, _("overMaxAlts"), config.autoBanAlts());
                    return;
                }
                // check passed, put data to playerCache
                else{
                    HashMap<String, String> tmp = new HashMap<String, String>();
                    if(s[0].equals("b")){
                        if (s.length == 8){
                            tmp.put("b", s[7]);
                        }else{
                            tmp.put("b", null);
                        }
                    }
                    if(Integer.parseInt(s[3]) > 0){
                        tmp.put("a", s[3]);
                        tmp.put("al", s[6]);
                    }
                    if(s[4].equals("y")){
                        tmp.put("m", "y");
                    }
                    if(Integer.parseInt(s[5]) > 0){
                        tmp.put("d", s[5]);
                    }
                    if (s.length == 8){
                       
                    }
                    
                    final Map<String, String> pass = Collections.unmodifiableMap(tmp);
                    Bukkit.getScheduler().runTask(plugin, new Runnable(){
                        @Override
                        public void run(){
                            notifyTask(name, pass);
                        }
                    });
                }
                plugin.debug(name + " authenticated with " + s[2] + " rep");
            }else{
                if (response.toString().contains("Server Disabled")) {
                    Util.message(Bukkit.getConsoleSender(), ChatColor.RED + "This Server Disabled by MCBans Administration!");
                    return;
                }

                if (config.isFailsafe()){
                    log.warning("Invalid response!(" + s.length + ") Kicked player: " + name);
                    kick(name, _("unavailable"));
                }else{
                    log.warning("Invalid response!(" + s.length + ") Check passed player: " + name);
                }
                log.warning("Response: " + response);
                return;
            }
        }
        catch (SocketTimeoutException ex){
            log.warning("Cannot connect MCBans API server: timeout");
            if (config.isFailsafe()){
                kick(name, _("unavailable"));
            }
        }
        catch (IOException ex){
            log.warning("Cannot connect MCBans API server!");
            if (config.isDebug()) ex.printStackTrace();

            if (config.isFailsafe()){
                kick(name, _("unavailable"));
            }
        }
        catch (Exception ex){
            log.warning("Error occurred in AsyncPlayerPreLoginEvent. Please report this!");
            ex.printStackTrace();

            if (config.isFailsafe()){
                log.warning("Internal exception! Kicked player: " + name);
                kick(name, _("unavailable"));
            }
        }
    }
    
    /**
     * Notify to player. Call this method in Synchronously by checkPlayerTask.
     * @param name
     * @param map
     */
    private void notifyTask(final String name, final Map<String, String> map){
        // Check target playre still online
        final Player player = Bukkit.getPlayerExact(name);
        if (player == null || !player.isOnline() || map == null){
            return;
        }
        
        if(map.containsKey("b")){
            Util.message(player, ChatColor.RED + _("bansOnRecord"));
            
            if (!Perms.HIDE_VIEW.has(player)){
                Perms.VIEW_BANS.message(ChatColor.RED + _("previousBans", I18n.PLAYER, player.getName()));
                
                String prev = map.get("b");
                if (config.isSendDetailPrevBans() && prev != null){
                    prev = prev.trim();
                    String[] bans = prev.split(",");
                    for (String ban : bans){
                        String[] data = ban.split("\\$");
                        if (data.length == 3){
                            Perms.VIEW_BANS.message(ChatColor.WHITE+ data[1] + ChatColor.GRAY + " .:. " + ChatColor.WHITE + data[0] + ChatColor.GRAY +  " (by " + data[2] + ")");
                        }
                    }
                }
            }
        }
        if(map.containsKey("d")){
            Util.message(player, ChatColor.RED + _("disputes", I18n.COUNT, map.get("d")));
        }
        if(map.containsKey("a")){
            if (!Perms.HIDE_VIEW.has(player))
                Perms.VIEW_ALTS.message(ChatColor.DARK_PURPLE + _("altAccounts", I18n.PLAYER, player.getName(), I18n.ALTS, map.get("al")));
        }
        if(map.containsKey("m")){
            //Util.broadcastMessage(ChatColor.AQUA + _("isMCBansMod", I18n.PLAYER, player.getName()));
            // notify to console, mcbans.view.staff, mcbans.admin, mcbans.ban.global players
            Util.message(Bukkit.getConsoleSender(), ChatColor.AQUA + player.getName() + " is a MCBans Staff member");
            
            plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable(){
                @Override
                public void run() {
                    Set<Player> players = Perms.VIEW_STAFF.getPlayers();
                    players.addAll(Perms.ADMIN.getPlayers());
                    players.addAll(Perms.BAN_GLOBAL.getPlayers());
                    for (final Player p : players){
                        if (p.canSee(player)){ // check joined player cansee
                            Util.message(p, ChatColor.AQUA + _("isMCBansMod", I18n.PLAYER, player.getName()));
                        }
                    }
                }
            }, 1L);
            
            // send information to mcbans staff
            Set<String> admins = new HashSet<String>();
            for (Player p : Perms.ADMIN.getPlayers()){
                admins.add(p.getName());
            }
            Util.message(player, ChatColor.AQUA + "You are a MCBans Staff Member! (ver " + plugin.getDescription().getVersion() + ")");
            Util.message(player, ChatColor.AQUA + "Online Admins: " + ((admins.size() > 0) ? Util.join(admins, ", ") : ChatColor.GRAY + "(none)"));
           
            // add online mcbans staff list array
            plugin.mcbStaff.add(player.getName());
        }
    }
    
    
    private void kick(final String name, final String reason){
        kick(name, reason, false);
    }
    
    private void kick(final String name, final String reason, final boolean addBan){
        Bukkit.getScheduler().runTask(plugin, new Runnable(){
            @Override
            public void run(){
                Player p = Bukkit.getPlayerExact(name);
                if (p != null && p.isOnline()){
                    p.kickPlayer(reason);
                }
                
                if (addBan) {
                    if (config.getAutoBanPlugin().equalsIgnoreCase("essentials")){
                        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "eban " + name + " " + reason);
                    }
                    else{
                        if (p != null && p.isOnline()){
                            p.setBanned(true);
                        }else{
                            OfflinePlayer target = Bukkit.getOfflinePlayer(name);
                            if (target != null){
                                target.setBanned(true);
                            }
                        }
                        log.info("Added " + name + " to bukkit ban list. (" + reason + ")");
                    }
                }
            }
        });
    }    
}