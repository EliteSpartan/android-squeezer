/*
 * Copyright (c) 2009 Google Inc.  All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.org.ngo.squeezer.service;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Base64;
import android.util.Log;
import android.widget.RemoteViews;

import com.crashlytics.android.Crashlytics;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.regex.Pattern;

import uk.org.ngo.squeezer.NowPlayingActivity;
import uk.org.ngo.squeezer.Preferences;
import uk.org.ngo.squeezer.R;
import uk.org.ngo.squeezer.RandomplayActivity;
import uk.org.ngo.squeezer.Util;
import uk.org.ngo.squeezer.framework.BaseActivity;
import uk.org.ngo.squeezer.framework.FilterItem;
import uk.org.ngo.squeezer.framework.PlaylistItem;
import uk.org.ngo.squeezer.itemlist.IServiceItemListCallback;
import uk.org.ngo.squeezer.itemlist.PluginItemListActivity;
import uk.org.ngo.squeezer.itemlist.dialog.AlbumViewDialog;
import uk.org.ngo.squeezer.itemlist.dialog.SongViewDialog;
import uk.org.ngo.squeezer.model.Album;
import uk.org.ngo.squeezer.model.Artist;
import uk.org.ngo.squeezer.model.Genre;
import uk.org.ngo.squeezer.model.MusicFolderItem;
import uk.org.ngo.squeezer.model.Player;
import uk.org.ngo.squeezer.model.PlayerState;
import uk.org.ngo.squeezer.model.PlayerState.ShuffleStatus;
import uk.org.ngo.squeezer.model.Playlist;
import uk.org.ngo.squeezer.model.Plugin;
import uk.org.ngo.squeezer.model.PluginItem;
import uk.org.ngo.squeezer.model.Song;
import uk.org.ngo.squeezer.model.Year;
import uk.org.ngo.squeezer.service.event.ConnectionChanged;
import uk.org.ngo.squeezer.service.event.HandshakeComplete;
import uk.org.ngo.squeezer.service.event.MusicChanged;
import uk.org.ngo.squeezer.service.event.PlayStatusChanged;
import uk.org.ngo.squeezer.service.event.PlayerStateChanged;
import uk.org.ngo.squeezer.service.event.PlayerVolume;
import uk.org.ngo.squeezer.service.event.PlayersChanged;
import uk.org.ngo.squeezer.service.event.PlaylistCreateFailed;
import uk.org.ngo.squeezer.service.event.PlaylistRenameFailed;
import uk.org.ngo.squeezer.service.event.PlaylistTracksAdded;
import uk.org.ngo.squeezer.service.event.PlaylistTracksDeleted;
import uk.org.ngo.squeezer.service.event.PowerStatusChanged;
import uk.org.ngo.squeezer.service.event.RepeatStatusChanged;
import uk.org.ngo.squeezer.service.event.ShuffleStatusChanged;
import uk.org.ngo.squeezer.service.event.SongTimeChanged;
import uk.org.ngo.squeezer.util.Scrobble;


public class SqueezeService extends Service implements ServiceCallbackList.ServicePublisher {

    private static final String TAG = "SqueezeService";

    private static final int PLAYBACKSERVICE_STATUS = 1;

    /** {@link java.util.regex.Pattern} that splits strings on spaces. */
    private static final Pattern mSpaceSplitPattern = Pattern.compile(" ");

    private static final String ALBUMTAGS = "alyj";

    /**
     * Information that will be requested about songs.
     * <p/>
     * a: artist name<br/>
     * C: compilation (1 if true, missing otherwise)<br/>
     * d: duration, in seconds<br/>
     * e: album ID<br/>
     * j: coverart (1 if available, missing otherwise)<br/>
     * J: artwork_track_id (if available, missing otherwise)<br/>
     * K: URL to remote artwork<br/>
     * l: album name<br/>
     * s: artist id<br/>
     * t: tracknum, if known<br/>
     * x: 1, if this is a remote track<br/>
     * y: song year<br/>
     * u: Song file url
     */
    // This should probably be a field in Song.
    private static final String SONGTAGS = "aCdejJKlstxyu";

    final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    Thread mainThread;

    /** Service-specific eventbus. All events generated by the service will be sent here. */
    final EventBus mEventBus = new EventBus();

    /** True if the handshake with the server has completed, otherwise false. */
    private volatile boolean mHandshakeComplete = false;

    /** Media session to associate with ongoing notifications. */
    private MediaSession mMediaSession;

    /**
     * Keeps track of all subscriptions, so we can cancel all subscriptions for a client at once
     */
    final Map<ServiceCallback, ServiceCallbackList> callbacks = new ConcurrentHashMap<ServiceCallback, ServiceCallbackList>();

    @Override
    public void addClient(ServiceCallbackList callbackList, ServiceCallback item) {
        callbacks.put(item, callbackList);
    }

    @Override
    public void removeClient(ServiceCallback item) {
        callbacks.remove(item);
    }

    final ConnectionState connectionState = new ConnectionState();

    final CliClient cli = new CliClient(this);

    /**
     * Is scrobbling enabled?
     */
    private boolean scrobblingEnabled;

    /**
     * Was scrobbling enabled?
     */
    private boolean scrobblingPreviouslyEnabled;

    /** Whether to show an on-going notification when a track is not playing. */
    boolean mShowNotificationWhenNotPlaying;

    int mFadeInSecs;

    private static final String ACTION_NEXT_TRACK = "uk.org.ngo.squeezer.service.ACTION_NEXT_TRACK";
    private static final String ACTION_PREV_TRACK = "uk.org.ngo.squeezer.service.ACTION_PREV_TRACK";
    private static final String ACTION_PLAY = "uk.org.ngo.squeezer.service.ACTION_PLAY";
    private static final String ACTION_PAUSE = "uk.org.ngo.squeezer.service.ACTION_PAUSE";
    private static final String ACTION_CLOSE = "uk.org.ngo.squeezer.service.ACTION_CLOSE";

    /**
     * Thrown when the service is asked to send a command to the server before the server
     * handshake completes.
     */
    public static class HandshakeNotCompleteException extends IllegalStateException {
        public HandshakeNotCompleteException() { super(); }
        public HandshakeNotCompleteException(String message) { super(message); }
        public HandshakeNotCompleteException(String message, Throwable cause) { super(message, cause); }
        public HandshakeNotCompleteException(Throwable cause) { super(cause); }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Get the main thread
        mainThread = Thread.currentThread();

        // Clear leftover notification in case this service previously got killed while playing
        NotificationManager nm = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        nm.cancel(PLAYBACKSERVICE_STATUS);
        connectionState
                .setWifiLock(((WifiManager) getSystemService(Context.WIFI_SERVICE)).createWifiLock(
                        WifiManager.WIFI_MODE_FULL, "Squeezer_WifiLock"));

        mEventBus.postSticky(new ConnectionChanged(ConnectionState.DISCONNECTED));
        cachePreferences();

        cli.initialize();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try{
            if(intent != null && intent.getAction()!= null ) {
                if (intent.getAction().equals(ACTION_NEXT_TRACK)) {
                    squeezeService.nextTrack();
                } else if (intent.getAction().equals(ACTION_PREV_TRACK)) {
                    squeezeService.previousTrack();
                } else if (intent.getAction().equals(ACTION_PLAY)) {
                    squeezeService.play();
                } else if (intent.getAction().equals(ACTION_PAUSE)) {
                    squeezeService.pause();
                } else if (intent.getAction().equals(ACTION_CLOSE)) {
                    squeezeService.disconnect();
                }
            }
        } catch(Exception e) {

        }
        return START_STICKY;
    }

    /**
     * Cache the value of various preferences.
     */
    private void cachePreferences() {
        final SharedPreferences preferences = getSharedPreferences(Preferences.NAME, MODE_PRIVATE);
        scrobblingEnabled = preferences.getBoolean(Preferences.KEY_SCROBBLE_ENABLED, false);
        mFadeInSecs = preferences.getInt(Preferences.KEY_FADE_IN_SECS, 0);
        mShowNotificationWhenNotPlaying = preferences
                .getBoolean(Preferences.KEY_NOTIFY_OF_CONNECTION, false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mMediaSession = new MediaSession(getApplicationContext(), "squeezer");
        }
        return (IBinder) squeezeService;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mMediaSession.release();
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
    }

    void disconnect() {
        disconnect(false);
    }

    void disconnect(boolean isServerDisconnect) {
        mEventBus.removeAllStickyEvents();
        connectionState.disconnect(this, isServerDisconnect && !mHandshakeComplete);
        mHandshakeComplete = false;
        clearOngoingNotification();
    }

    private interface CmdHandler {
        void handle(List<String> tokens);
    }

    private Map<String, CmdHandler> initializeGlobalHandlers() {
        Map<String, CmdHandler> handlers = new HashMap<String, CmdHandler>();

        for (final CliClient.ExtendedQueryFormatCmd cmd : cli.extQueryFormatCmds) {
            if (cmd.handlerList == CliClient.HANDLER_LIST_GLOBAL) {
                handlers.put(cmd.cmd, new CmdHandler() {
                    @Override
                    public void handle(List<String> tokens) {
                        cli.parseSqueezerList(cmd, tokens);
                    }
                });
            }
        }
        handlers.put("playlists", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                if ("delete".equals(tokens.get(1))) {
                    ;
                } else if ("edit".equals(tokens.get(1))) {
                    ;
                } else if ("new".equals(tokens.get(1))) {
                    HashMap<String, String> tokenMap = parseTokens(tokens);
                    if (tokenMap.get("overwritten_playlist_id") != null) {
                        mEventBus.post(new PlaylistCreateFailed(getString(R.string.PLAYLIST_EXISTS_MESSAGE,
                                tokenMap.get("name"))));
                    }
                } else if ("rename".equals(tokens.get(1))) {
                    HashMap<String, String> tokenMap = parseTokens(tokens);
                    if (tokenMap.get("dry_run") != null) {
                        if (tokenMap.get("overwritten_playlist_id") != null) {
                            mEventBus.post(new PlaylistRenameFailed(getString(R.string.PLAYLIST_EXISTS_MESSAGE,
                                        tokenMap.get("newname"))));
                        } else {
                            cli.sendCommandImmediately(
                                    "playlists rename playlist_id:" + tokenMap.get("playlist_id")
                                            + " newname:" + Util.encode(tokenMap.get("newname")));
                        }
                    }
                } else if ("tracks".equals(tokens.get(1))) {
                    cli.parseSqueezerList(cli.extQueryFormatCmdMap.get("playlists tracks"), tokens);
                } else {
                    cli.parseSqueezerList(cli.extQueryFormatCmdMap.get("playlists"), tokens);
                }
            }
        });
        handlers.put("login", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.i(TAG, "Authenticated: " + tokens);
                onAuthenticated();
            }
        });
        handlers.put("pref", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.i(TAG, "Preference received: " + tokens);
                if ("httpport".equals(tokens.get(1)) && tokens.size() >= 3) {
                    connectionState.setHttpPort(Integer.parseInt(tokens.get(2)));
                }
                if ("jivealbumsort".equals(tokens.get(1)) && tokens.size() >= 3) {
                    connectionState.setPreferedAlbumSort(tokens.get(2));
                }
                if ("mediadirs".equals(tokens.get(1)) && tokens.size() >= 3) {
                    connectionState.setMediaDirs(Util.decode(tokens.get(2)));
                }
            }
        });
        handlers.put("can", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.i(TAG, "Capability received: " + tokens);
                if ("favorites".equals(tokens.get(1)) && tokens.size() >= 4) {
                    connectionState.setCanFavorites(Util.parseDecimalIntOrZero(tokens.get(3)) == 1);
                }
                if ("musicfolder".equals(tokens.get(1)) && tokens.size() >= 3) {
                    connectionState
                            .setCanMusicfolder(Util.parseDecimalIntOrZero(tokens.get(2)) == 1);
                }
                if ("myapps".equals(tokens.get(1)) && tokens.size() >= 4) {
                    connectionState.setCanMyApps(Util.parseDecimalIntOrZero(tokens.get(3)) == 1);
                }
                if ("randomplay".equals(tokens.get(1)) && tokens.size() >= 3) {
                    connectionState
                            .setCanRandomplay(Util.parseDecimalIntOrZero(tokens.get(2)) == 1);
                }
            }
        });
        handlers.put("getstring", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                int maxOrdinal = 0;
                Map<String, String> tokenMap = parseTokens(tokens);
                for (Entry<String, String> entry : tokenMap.entrySet()) {
                    if (entry.getValue() != null) {
                        ServerString serverString = ServerString.valueOf(entry.getKey());
                        serverString.setLocalizedString(entry.getValue());
                        if (serverString.ordinal() > maxOrdinal) {
                            maxOrdinal = serverString.ordinal();
                        }
                    }
                }

                // Fetch the next strings until the list is completely translated
                if (maxOrdinal < ServerString.values().length - 1) {
                    cli.sendCommandImmediately(
                            "getstring " + ServerString.values()[maxOrdinal + 1].name());
                }
            }
        });
        handlers.put("version", new CmdHandler() {
            /**
             * Seeing the <code>version</code> result indicates that the
             * handshake has completed (see
             * {@link SqueezeService#onCliPortConnectionEstablished(String, String)}),
             * post a {@link HandshakeComplete} event.
             */
            @Override
            public void handle(List<String> tokens) {
                Log.i(TAG, "Version received: " + tokens);
                Crashlytics.setString("server_version", tokens.get(1));
                mHandshakeComplete = true;
                strings();

                mEventBus.postSticky(new HandshakeComplete(
                        connectionState.canFavorites(), connectionState.canMusicfolder(),
                        connectionState.canMusicfolder(), connectionState.canRandomplay()));
            }
        });

        return handlers;
    }

    private Map<String, CmdHandler> initializePrefixedHandlers() {
        Map<String, CmdHandler> handlers = new HashMap<String, CmdHandler>();

        for (final CliClient.ExtendedQueryFormatCmd cmd : cli.extQueryFormatCmds) {
            if (cmd.handlerList == CliClient.HANDLER_LIST_PREFIXED) {
                handlers.put(cmd.cmd, new CmdHandler() {
                    @Override
                    public void handle(List<String> tokens) {
                        cli.parseSqueezerList(cmd, tokens);
                    }
                });
            }
        }

        return handlers;
    }

    private Map<String, CmdHandler> initializePlayerSpecificHandlers() {
        Map<String, CmdHandler> handlers = new HashMap<String, CmdHandler>();

        for (final CliClient.ExtendedQueryFormatCmd cmd : cli.extQueryFormatCmds) {
            if (cmd.handlerList == CliClient.HANDLER_LIST_PLAYER_SPECIFIC) {
                handlers.put(cmd.cmd, new CmdHandler() {
                    @Override
                    public void handle(List<String> tokens) {
                        cli.parseSqueezerList(cmd, tokens);
                    }
                });
            }
        }
        handlers.put("play", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.v(TAG, "play registered");
                updatePlayStatus(PlayerState.PLAY_STATE_PLAY);
            }
        });
        handlers.put("stop", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.v(TAG, "stop registered");
                updatePlayStatus(PlayerState.PLAY_STATE_STOP);
            }
        });
        handlers.put("pause", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.v(TAG, "pause registered: " + tokens);
                parsePause(tokens.size() >= 3 ? tokens.get(2) : null);
            }
        });
        handlers.put("playlist", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                parsePlaylistNotification(tokens);
            }
        });

        return handlers;
    }

    private Map<String, CmdHandler> initializeGlobalPlayerSpecificHandlers() {
        Map<String, CmdHandler> handlers = new HashMap<String, CmdHandler>();

        handlers.put("client", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.i(TAG, "client received: " + tokens);
                // Something has happened to the player list, we just fetch the full list again
                // This is simpler and handles any missed client events
                fetchPlayers();
            }
        });
        handlers.put("status", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                if (tokens.size() >= 3 && "-".equals(tokens.get(2))) {
                    Player player = connectionState.getPlayer(Util.decode(tokens.get(0)));

                    // XXX: Can we ever see a status for a player we don't know about?
                    // XXX: Maybe the better thing to do is to add it.
                    if (player == null)
                        return;

                    PlayerState playerState = player.getPlayerState();

                    HashMap<String, String> tokenMap = parseTokens(tokens);

                    boolean unknownRepeatStatus = playerState.getRepeatStatus() == null;
                    boolean unknownShuffleStatus = playerState.getShuffleStatus() == null;

                    boolean changedPower = playerState.setPoweredOn(Util.parseDecimalIntOrZero(tokenMap.get("power")) == 1);
                    boolean changedShuffleStatus = playerState.setShuffleStatus(tokenMap.get("playlist shuffle"));
                    boolean changedRepeatStatus = playerState.setRepeatStatus(tokenMap.get("playlist repeat"));
                    boolean changedCurrentPlaylistIndex = playerState.setCurrentPlaylistIndex(Util.parseDecimalIntOrZero(tokenMap.get("playlist_cur_index")));
                    boolean changedCurrentPlaylist = playerState.setCurrentPlaylist(tokenMap.get("playlist_name"));
                    boolean changedSleep = playerState.setSleep(Util.parseDecimalIntOrZero(tokenMap.get("will_sleep_in")));
                    boolean changedSleepDuration = playerState.setSleepDuration(Util.parseDecimalIntOrZero(tokenMap.get("sleep")));
                    boolean changedSong = playerState.setCurrentSong(new Song(tokenMap));
                    boolean changedSongDuration = playerState.setCurrentSongDuration(Util.parseDecimalIntOrZero(tokenMap.get("duration")));
                    boolean changedSongTime = playerState.setCurrentTimeSecond(Util.parseDecimalIntOrZero(tokenMap.get("time")));
                    boolean changedVolume = playerState.setCurrentVolume(Util.parseDecimalIntOrZero(tokenMap.get("mixer volume")));
                    boolean changedSyncMaster = playerState.setSyncMaster(tokenMap.get("sync_master"));
                    boolean changedSyncSlaves = playerState.setSyncSlaves(Splitter.on(",").omitEmptyStrings().splitToList(Strings.nullToEmpty(tokenMap.get("sync_slaves"))));
                    boolean changedSubscription = playerState.setSubscriptionType(tokenMap.get("subscribe"));

                    player.setPlayerState(playerState);

                    // Kept as its own method because other methods call it, unlike the explicit
                    // calls to the callbacks below.
                    updatePlayStatus(tokenMap.get("mode"), player);

                    updatePlayerSubscription(player, calculateSubscriptionTypeFor(player));

                    // Note to self: The problem here is that with second-to-second updates enabled
                    // the playerlistactivity callback will be called every second.  Thinking that
                    // a better approach would be for clients to register a single callback and a
                    // bitmask of events they're interested in based on the change* variables.
                    // Each callback would be called a maximum of once, with the new player and a
                    // bitmask that corresponds to which changes happened (so the client can
                    // distinguish between the types of changes).

                    // Might also be worth investigating Otto as an event bus instead.

                    // Quick and dirty fix -- only call onPlayerStateReceived for changes to the
                    // player state (ignore changes to Song, SongDuration, SongTime).

                    if (changedPower || changedSleep || changedSleepDuration || changedVolume
                            || changedSong || changedSyncMaster || changedSyncSlaves) {
                        mEventBus.post(new PlayerStateChanged(player, playerState));
                    }

                    if (player.getId().equals(getActivePlayerId())) {
                        // Power status
                        if (changedPower) {
                            mEventBus.post(new PowerStatusChanged(
                                    squeezeService.canPowerOn(),
                                    squeezeService.canPowerOff()));
                        }

                        // Current song
                        if (changedSong) {
                            updateOngoingNotification();
                            mEventBus.postSticky(new MusicChanged(playerState));
                        }

                        // Shuffle status.
                        if (changedShuffleStatus) {
                            mEventBus.post(new ShuffleStatusChanged(
                                    unknownShuffleStatus, playerState.getShuffleStatus()));
                        }

                        // Repeat status.
                        if (changedRepeatStatus) {
                            mEventBus.post(new RepeatStatusChanged(
                                    unknownRepeatStatus, playerState.getRepeatStatus()));
                        }

                        // Position in song
                        if (changedSongDuration || changedSongTime) {
                            mEventBus.post(new SongTimeChanged(
                                    playerState.getCurrentTimeSecond(),
                                    playerState.getCurrentSongDuration()));
                        }
                    }
                } else {
                    cli.parseSqueezerList(cli.extQueryFormatCmdMap.get("status"), tokens);
                }
            }
        });
        handlers.put("prefset", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.v(TAG, "Prefset received: " + tokens);
                if (tokens.size() > 4 && "server".equals(tokens.get(2)) && "volume".equals(
                        tokens.get(3))) {
                    String playerId = Util.decode(tokens.get(0));
                    int newVolume = Util.parseDecimalIntOrZero(tokens.get(4));
                    updatePlayerVolume(playerId, newVolume);
                }
            }
        });

        return handlers;
    }

    private Map<String, CmdHandler> initializePrefixedPlayerSpecificHandlers() {
        Map<String, CmdHandler> handlers = new HashMap<String, CmdHandler>();

        for (final CliClient.ExtendedQueryFormatCmd cmd : cli.extQueryFormatCmds) {
            if (cmd.handlerList == CliClient.HANDLER_LIST_PREFIXED_PLAYER_SPECIFIC) {
                handlers.put(cmd.cmd, new CmdHandler() {
                    @Override
                    public void handle(List<String> tokens) {
                        cli.parseSqueezerList(cmd, tokens);
                    }
                });
            }
        }

        return handlers;
    }

    private final Map<String, CmdHandler> globalHandlers = initializeGlobalHandlers();

    private final Map<String, CmdHandler> prefixedHandlers = initializePrefixedHandlers();

    private final Map<String, CmdHandler> playerSpecificHandlers
            = initializePlayerSpecificHandlers();

    private final Map<String, CmdHandler> globalPlayerSpecificHandlers
            = initializeGlobalPlayerSpecificHandlers();

    private final Map<String, CmdHandler> prefixedPlayerSpecificHandlers
            = initializePrefixedPlayerSpecificHandlers();

    void onLineReceived(String serverLine) {
        Log.v(TAG, "RECV: " + serverLine);

        // Make sure that username/password do not make it to Crashlytics.
        if (serverLine.startsWith("login ")) {
            Crashlytics.setString("lastReceivedLine", "login [username] [password]");
        } else {
            Crashlytics.setString("lastReceivedLine", serverLine);
        }

        List<String> tokens = Arrays.asList(mSpaceSplitPattern.split(serverLine));
        if (tokens.size() < 2) {
            return;
        }

        CmdHandler handler;
        if ((handler = globalHandlers.get(tokens.get(0))) != null) {
            handler.handle(tokens);
            return;
        }
        if ((handler = prefixedHandlers.get(tokens.get(1))) != null) {
            handler.handle(tokens);
            return;
        }
        if ((handler = globalPlayerSpecificHandlers.get(tokens.get(1))) != null) {
            handler.handle(tokens);
            return;
        }

        // Player-specific commands for our active player.
        if (Util.decode(tokens.get(0)).equals(getActivePlayerId())) {
            if ((handler = playerSpecificHandlers.get(tokens.get(1))) != null) {
                handler.handle(tokens);
                return;
            }
            if (tokens.size() > 2
                    && (handler = prefixedPlayerSpecificHandlers.get(tokens.get(2))) != null) {
                handler.handle(tokens);
            }
        }
    }

    private String getActivePlayerId() {
        return (connectionState.getActivePlayer() != null ? connectionState
                .getActivePlayer().getId() : null);
    }

    private void updatePlayerVolume(String playerId, int newVolume) {
        Player player = connectionState.getPlayer(playerId);
        if (player == null)
            return;
        connectionState.getPlayer(playerId).getPlayerState().setCurrentVolume(newVolume);
        mEventBus.post(new PlayerVolume(newVolume, player));
    }

    private void parsePlaylistNotification(List<String> tokens) {
        Log.v(TAG, "Playlist notification received: " + tokens);
        String notification = tokens.get(2);
        if ("newsong".equals(notification)) {
            // When we don't subscribe to the current players status, we rely
            // on playlist notifications and order song details here.
            // TODO keep track of subscribe status
            cli.sendActivePlayerCommand("status - 1 tags:" + SONGTAGS);
        } else if ("play".equals(notification)) {
            updatePlayStatus(PlayerState.PLAY_STATE_PLAY);
        } else if ("stop".equals(notification)) {
            updatePlayStatus(PlayerState.PLAY_STATE_STOP);
        } else if ("pause".equals(notification)) {
            parsePause(tokens.size() >= 4 ? tokens.get(3) : null);
        } else if ("addtracks".equals(notification)) {
            mEventBus.postSticky(new PlaylistTracksAdded());
        } else if ("delete".equals(notification)) {
            mEventBus.postSticky(new PlaylistTracksDeleted());
        }
    }

    private void parsePause(String explicitPause) {
        if ("0".equals(explicitPause)) {
            updatePlayStatus(PlayerState.PLAY_STATE_PLAY);
        } else if ("1".equals(explicitPause)) {
            updatePlayStatus(PlayerState.PLAY_STATE_PAUSE);
        }
        updateAllPlayerSubscriptionStates();
    }

    private HashMap<String, String> parseTokens(List<String> tokens) {
        HashMap<String, String> tokenMap = new HashMap<String, String>();
        String[] kv;
        for (String token : tokens) {
            kv = parseToken(token);
            if (kv.length == 0)
                continue;

            tokenMap.put(kv[0], kv[1]);
        }
        return tokenMap;
    }

    /**
     * Parse a token in to a key-value pair.  The value is optional.
     * <p/>
     * The token is assumed to be URL encoded, with the key and value separated by ':' (encoded
     * as '%3A').
     *
     * @param token The string to decode.
     * @return An array -- empty if token is null or empty, otherwise with two elements. The first
     * is the key, the second, which may be null, is the value. The elements are decoded.
     */
    private String[] parseToken(@Nullable String token) {
        String key, value;

        if (token == null || token.length() == 0) {
            return new String[]{};
        }

        int colonPos = token.indexOf("%3A");
        if (colonPos == -1) {
            key = Util.decode(token);
            value = null;
        } else {
            key = Util.decode(token.substring(0, colonPos));
            value = Util.decode(token.substring(colonPos + 3));
        }

        return new String[]{key, value};
    }

    /**
     * Updates the playing status of the current player.
     * <p/>
     * Updates the Wi-Fi lock and ongoing status notification as necessary.
     * <p/>
     * Posts a {@link PlayStatusChanged} message to event listeners.
     *
     * @param playStatus The new playing status.
     */
    private void updatePlayStatus(@PlayerState.PlayState String playStatus) {
        updatePlayStatus(playStatus, connectionState.getActivePlayer());
    }

    private void updatePlayStatus(String playStatus, Player player) {
        if (playStatus == null)
            return;

        // Handle unknown states.
        if (!playStatus.equals(PlayerState.PLAY_STATE_PLAY) &&
                !playStatus.equals(PlayerState.PLAY_STATE_PAUSE) &&
                !playStatus.equals(PlayerState.PLAY_STATE_STOP)) {
            return;
        }

        PlayerState playerState = player.getPlayerState();

        if (playerState.setPlayStatus(playStatus)) {
            if (player == connectionState.getActivePlayer()) {
                connectionState.updateWifiLock(playerState.isPlaying());
                updateOngoingNotification();
                mEventBus.post(new PlayStatusChanged(playStatus));
            }
        }
    }

    /**
     * Updates the shuffle status of the current player.
     * <p/>
     * If the shuffle status has changed then posts a
     * {@link ShuffleStatusChanged} message.
     *
     * @param shuffleStatus The new shuffle status.
     */
    private void updateShuffleStatus(ShuffleStatus shuffleStatus) {
        if (shuffleStatus != null && shuffleStatus != connectionState.getActivePlayerState().getShuffleStatus()) {
            boolean wasUnknown = connectionState.getActivePlayerState().getShuffleStatus() == null;
            connectionState.getActivePlayerState().setShuffleStatus(shuffleStatus);
            mEventBus.post(new ShuffleStatusChanged(wasUnknown, shuffleStatus));
        }
    }

    /**
     * Change the player that is controlled by Squeezer (the "active" player).
     *
     * @param newActivePlayer May be null, in which case no players are controlled.
     */
    void changeActivePlayer(@Nullable final Player newActivePlayer) {
        Player prevActivePlayer = connectionState.getActivePlayer();

        // Do nothing if they player hasn't actually changed.
        if (prevActivePlayer == newActivePlayer) {
            return;
        }

        connectionState.setActivePlayer(newActivePlayer);
        Log.i(TAG, "Active player now: " + newActivePlayer);

        // If this is a new player then start an async fetch of its status.
        if (newActivePlayer != null) {
            cli.sendActivePlayerCommand("status - 1 tags:" + SONGTAGS);
        }

        updateAllPlayerSubscriptionStates();

        // NOTE: this involves a write and can block (sqlite lookup via binder call), so
        // should be done off-thread, so we can process service requests & send our callback
        // as quickly as possible.
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final SharedPreferences preferences = getSharedPreferences(Preferences.NAME,
                        MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();

                if (newActivePlayer == null) {
                    Log.v(TAG, "Clearing " + Preferences.KEY_LAST_PLAYER);
                    editor.remove(Preferences.KEY_LAST_PLAYER);
                } else {
                    Log.v(TAG, "Saving " + Preferences.KEY_LAST_PLAYER + "=" + newActivePlayer.getId());
                    editor.putString(Preferences.KEY_LAST_PLAYER, newActivePlayer.getId());
                }

                editor.commit();
            }
        });

        List<Player> players = connectionState.getPlayers();
        mEventBus.postSticky(new PlayersChanged(players, newActivePlayer));
    }

    /**
     * Adjusts the subscription to players' status updates.
     */
    private void updateAllPlayerSubscriptionStates() {
        for (Player player : connectionState.getPlayers()) {
            updatePlayerSubscription(player, calculateSubscriptionTypeFor(player));
        }
    }

    /**
     * Determine the correct status subscription type for the given player, based on
     * how frequently we need to know its status.
     */
    private @PlayerState.PlayerSubscriptionType String calculateSubscriptionTypeFor(Player player) {
        Player activePlayer = connectionState.getActivePlayer();

        if (mEventBus.hasSubscriberForEvent(PlayerStateChanged.class) ||
                (mEventBus.hasSubscriberForEvent(SongTimeChanged.class) && player.equals(activePlayer))) {
            if (player.equals(activePlayer)) {
                // If it's the active player then get second-to-second updates.
                return PlayerState.NOTIFY_REAL_TIME;
            } else {
                // For other players get updates only when the player status changes...
                // ... unless the player has a sleep duration set. In that case we need
                // real_time updates, as on_change events are not fired as the will_sleep_in
                // timer counts down.
                if (player.getPlayerState().getSleep() > 0) {
                    return PlayerState.NOTIFY_REAL_TIME;
                } else {
                    return PlayerState.NOTIFY_ON_CHANGE;
                }
            }
        } else {
            // Disable subscription for this player's status updates.
            return PlayerState.NOTIFY_NONE;
        }
    }

    /**
     * Manage subscription to a player's status updates.
     *
     * @param player player to manage.
     * @param playerSubscriptionType the new subscription type
     */
    private void updatePlayerSubscription(
            Player player,
            @NonNull @PlayerState.PlayerSubscriptionType String playerSubscriptionType) {
        PlayerState playerState = player.getPlayerState();

        // Do nothing if the player subscription type hasn't changed. This prevents sending a
        // subscription update "status" message which will be echoed back by the server and
        // trigger processing of the status message by the service.
        if (playerState != null) {
            if (playerState.getSubscriptionType().equals(playerSubscriptionType)) {
                return;
            }
        }

        cli.sendPlayerCommand(player, "status - 1 subscribe:" + playerSubscriptionType + " tags:" + SONGTAGS);
    }

    /**
     * Manages the state of any ongoing notification based on the player and connection state.
     */
    private void updateOngoingNotification() {
        Player activePlayer = connectionState.getActivePlayer();
        PlayerState activePlayerState = connectionState.getActivePlayerState();

        // Update scrobble state, if either we're currently scrobbling, or we
        // were (to catch the case where we started scrobbling a song, and the
        // user went in to settings to disable scrobbling).
        if (scrobblingEnabled || scrobblingPreviouslyEnabled) {
            scrobblingPreviouslyEnabled = scrobblingEnabled;
            Scrobble.scrobbleFromPlayerState(this, activePlayerState);
        }

        // If there's no active player then kill the notification and get out.
        if (activePlayer == null || activePlayerState == null) {
            clearOngoingNotification();
            return;
        }

        boolean playing = activePlayerState.isPlaying();

        // If the song is not playing and the user wants notifications only when playing then
        // kill the notification and get out.
        if (!playing && !mShowNotificationWhenNotPlaying) {
            clearOngoingNotification();
            return;
        }

        Song currentSong = activePlayerState.getCurrentSong();
        String songName = currentSong.getName();
        String albumName = currentSong.getAlbumName();
        String artistName = currentSong.getArtist();
        String url = currentSong.getArtworkUrl(squeezeService);
        String playerName = activePlayer.getName();

        NotificationManagerCompat nm = NotificationManagerCompat.from(this);

        PendingIntent nextPendingIntent = getPendingIntent(ACTION_NEXT_TRACK);
        PendingIntent prevPendingIntent = getPendingIntent(ACTION_PREV_TRACK);
        PendingIntent playPendingIntent = getPendingIntent(ACTION_PLAY);
        PendingIntent pausePendingIntent = getPendingIntent(ACTION_PAUSE);
        PendingIntent closePendingIntent = getPendingIntent(ACTION_CLOSE);

        Bitmap albumArt = null;
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-agent", "Mozilla/4.0");

            connection.connect();
            InputStream input = connection.getInputStream();

            albumArt = BitmapFactory.decodeStream(input);

        } catch (Exception e) {
            Log.w(TAG, "Exception when fetching notification icon: " + e);
            Crashlytics.logException(e);
            albumArt = BitmapFactory.decodeResource(getResources(), R.drawable.icon_album_noart);
        }

        Intent showNowPlaying = new Intent(this, NowPlayingActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pIntent = PendingIntent.getActivity(this, 0, showNowPlaying, 0);

        Notification notification;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Notification.Builder builder = new Notification.Builder(this);
            builder.setContentIntent(pIntent);
            builder.setSmallIcon(R.drawable.squeezer_notification);
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
            builder.setShowWhen(false);
            builder.setContentTitle(songName);
            builder.setContentText(albumName);
            builder.setSubText(playerName);
            builder.setLargeIcon(albumArt);
            builder.setStyle(new Notification.MediaStyle()
                    .setShowActionsInCompactView(1, 2)
                    .setMediaSession(mMediaSession.getSessionToken()));

            MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder();
            metaBuilder.putString(MediaMetadata.METADATA_KEY_ARTIST, artistName);
            metaBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM, albumName);
            metaBuilder.putString(MediaMetadata.METADATA_KEY_TITLE, songName);
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, albumArt);
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, albumArt);
            mMediaSession.setMetadata(metaBuilder.build());

            if (playing) {
                builder.setOngoing(true)
                        .addAction(new Notification.Action(R.drawable.ic_action_previous, "Previous", prevPendingIntent))
                        .addAction(new Notification.Action(R.drawable.ic_action_pause, "Pause", pausePendingIntent))
                        .addAction(new Notification.Action(R.drawable.ic_action_next, "Next", nextPendingIntent));
            } else {
                builder.setOngoing(false)
                        .setDeleteIntent(closePendingIntent)
                        .addAction(new Notification.Action(R.drawable.ic_action_previous, "Previous", prevPendingIntent))
                        .addAction(new Notification.Action(R.drawable.ic_action_play, "Play", playPendingIntent))
                        .addAction(new Notification.Action(R.drawable.ic_action_next, "Next", nextPendingIntent));
            }
            notification = builder.build();
        } else {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this);

            builder.setOngoing(true);
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE);
            builder.setSmallIcon(R.drawable.squeezer_notification);

            RemoteViews normalView = new RemoteViews(this.getPackageName(), R.layout.notification_player_normal);
            RemoteViews expandedView = new RemoteViews(this.getPackageName(), R.layout.notification_player_expanded);

            normalView.setOnClickPendingIntent(R.id.next, nextPendingIntent);

            expandedView.setOnClickPendingIntent(R.id.previous, prevPendingIntent);
            expandedView.setOnClickPendingIntent(R.id.next, nextPendingIntent);

            builder.setContent(normalView);

            normalView.setImageViewBitmap(R.id.album, albumArt);
            expandedView.setImageViewBitmap(R.id.album, albumArt);

            normalView.setTextViewText(R.id.trackname, songName);
            normalView.setTextViewText(R.id.albumname, albumName);

            expandedView.setTextViewText(R.id.trackname, songName);
            expandedView.setTextViewText(R.id.albumname, albumName);
            expandedView.setTextViewText(R.id.player_name, playerName);

            if (playing) {
                normalView.setImageViewResource(R.id.pause, R.drawable.ic_action_pause);
                normalView.setOnClickPendingIntent(R.id.pause, pausePendingIntent);

                expandedView.setImageViewResource(R.id.pause, R.drawable.ic_action_pause);
                expandedView.setOnClickPendingIntent(R.id.pause, pausePendingIntent);
            } else {
                normalView.setImageViewResource(R.id.pause, R.drawable.ic_action_play);
                normalView.setOnClickPendingIntent(R.id.pause, playPendingIntent);

                expandedView.setImageViewResource(R.id.pause, R.drawable.ic_action_play);
                expandedView.setOnClickPendingIntent(R.id.pause, playPendingIntent);
            }

            builder.setContentTitle(songName);
            builder.setContentText(getString(R.string.notification_playing_text, playerName));
            builder.setContentIntent(pIntent);

            notification = builder.build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                notification.bigContentView = expandedView;
            }
        }

        nm.notify(PLAYBACKSERVICE_STATUS, notification);
    }

    /**
     * @param action The action to be performed.
     * @return A new {@link PendingIntent} for {@literal action} that will update any existing
     *     intents that use the same action.
     */
    @NonNull
    private PendingIntent getPendingIntent(@NonNull String action){
        Intent intent = new Intent(this, SqueezeService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void clearOngoingNotification() {
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        nm.cancel(PLAYBACKSERVICE_STATUS);
    }

    /**
     * Authenticate on the SqueezeServer.
     * <p/>
     * The server does
     * <pre>
     * login user wrongpassword
     * login user ******
     * (Connection terminated)
     * </pre>
     * instead of as documented
     * <pre>
     * login user wrongpassword
     * (Connection terminated)
     * </pre>
     * therefore a disconnect when handshake (the next step after authentication) is not completed,
     * is considered an authentication failure.
     */
    void onCliPortConnectionEstablished(final String userName, final String password) {
        connectionState.setConnectionState(this, ConnectionState.LOGIN_STARTED);
        cli.sendCommandImmediately("login " + Util.encode(userName) + " " + Util.encode(password));
    }

    /**
     * Handshake with the SqueezeServer, learn some of its supported features, and start listening
     * for asynchronous updates of server state.
     *
     * Note: Authentication may not actually have completed at this point. The server has
     * responded to the "login" request, but if the username/password pair was incorrect it
     * has (probably) not yet disconnected the socket. See
     * {@link uk.org.ngo.squeezer.service.ConnectionState.ListeningThread#run()} for the code
     * that determines whether authentication succeeded.
     */
    private void onAuthenticated() {
        fetchPlayers();
        cli.sendCommandImmediately(
                "listen 1", // subscribe to all server notifications
                "can musicfolder ?", // learn music folder browsing support
                "can randomplay ?", // learn random play function functionality
                "can favorites items ?", // learn support for "Favorites" plugin
                "can myapps items ?", // learn support for "MyApps" plugin
                "pref httpport ?", // learn the HTTP port (needed for images)
                "pref jivealbumsort ?", // learn the preferred album sort order
                "pref mediadirs ?", // learn the base path(s) of the server music library

                // Fetch the version number. This must be the last thing
                // fetched, as seeing the result triggers the
                // "handshake is complete" logic elsewhere.
                "version ?"
        );
    }

    /**
     * Start an asynchronous fetch of all players from the server.
     */
    private void fetchPlayers() {
        // Unsubscribe to any existing player states, and clear the list of
        for (Player player : connectionState.getPlayers()) {
            updatePlayerSubscription(player, PlayerState.NOTIFY_NONE);
        }

        connectionState.clearPlayers();

        // Initiate an async player fetch
        cli.requestItems("players", -1, new IServiceItemListCallback<Player>() {
            @Override
            public void onItemsReceived(int count, int start, Map<String, String> parameters,
                    List<Player> items, Class<Player> dataType) {
                connectionState.addPlayers(items);

                // If all players have been received then determine the new active player.
                if (start + items.size() >= count) {
                    Player initialPlayer = getInitialPlayer();
                    if (initialPlayer != null) {
                        // Note: changeActivePlayer() posts a PlayersChanged event.
                        changeActivePlayer(initialPlayer);
                    }
                }
            }

            /**
             * @return The player that should be chosen as the active player. This is either the
             *     last active player (if known), the first player the server knows about if
             *     there are connected players, or null if there are no connected players.
             */
            @Nullable
            private Player getInitialPlayer() {
                final SharedPreferences preferences = getSharedPreferences(Preferences.NAME,
                        Context.MODE_PRIVATE);
                final String lastConnectedPlayer = preferences.getString(Preferences.KEY_LAST_PLAYER,
                        null);
                Log.i(TAG, "lastConnectedPlayer was: " + lastConnectedPlayer);

                List<Player> players = connectionState.getPlayers();
                for (Player player : players) {
                    if (player.getId().equals(lastConnectedPlayer)) {
                        return player;
                    }
                }
                return !players.isEmpty() ? players.get(0) : null;
            }

            @Override
            public Object getClient() {
                return SqueezeService.this;
            }
        });
    }

    /* Start an asynchronous fetch of the squeezeservers localized strings */
    private void strings() {
        cli.sendCommandImmediately("getstring " + ServerString.values()[0].name());
    }

    /** A download request will be passed to the download manager for each song called back to this */
    private final IServiceItemListCallback<Song> songDownloadCallback = new IServiceItemListCallback<Song>() {
        @Override
        public void onItemsReceived(int count, int start, Map<String, String> parameters, List<Song> items, Class<Song> dataType) {
            for (Song item : items) {
                downloadSong(item.getId(), item.getName(), item.getUrl());
            }
        }

        @Override
        public Object getClient() {
            return this;
        }
    };

    /**
     * For each item called to this:
     * If it is a folder: recursive lookup items in the folder
     * If is is a track: Enqueue a download request to the download manager
     */
    private final IServiceItemListCallback<MusicFolderItem> musicFolderDownloadCallback = new IServiceItemListCallback<MusicFolderItem>() {
        @Override
        public void onItemsReceived(int count, int start, Map<String, String> parameters, List<MusicFolderItem> items, Class<MusicFolderItem> dataType) {
            for (MusicFolderItem item : items) {
                squeezeService.downloadItem(item);
            }
        }

        @Override
        public Object getClient() {
            return this;
        }
    };

    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private void downloadSong(String songId, String title, @NonNull String serverUrl) {
        if (songId == null) {
            return;
        }

        // If running on Gingerbread or greater use the Download Manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri uri = Uri.parse(squeezeService.getSongDownloadUrl(songId));
            DownloadDatabase downloadDatabase = new DownloadDatabase(this);
            String localPath = getLocalFile(serverUrl);
            String tempFile = UUID.randomUUID().toString();
            String credentials = connectionState.getUserName() + ":" + connectionState.getPassword();
            String base64EncodedCredentials = Base64.encodeToString(credentials.getBytes(), Base64.NO_WRAP);
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle(title)
                    .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MUSIC, tempFile)
                    .setVisibleInDownloadsUi(false)
                    .addRequestHeader("Authorization", "Basic " + base64EncodedCredentials);
            long downloadId = downloadManager.enqueue(request);

            Crashlytics.log("Registering new download");
            Crashlytics.log("downloadId: " + downloadId);
            Crashlytics.log("tempFile: " + tempFile);
            Crashlytics.log("localPath: " + localPath);

            if (!downloadDatabase.registerDownload(downloadId, tempFile, localPath)) {
                Crashlytics.log(Log.WARN, TAG, "Could not register download entry for: " + downloadId);
                downloadManager.remove(downloadId);
            }
        }
    }

    /**
     * Tries to get the path relative to the server music library.
     * <p/>
     * If this is not possible resort to the last path segment of the server path.
     * In both cases replace dangerous characters by safe ones.
     */
    private String getLocalFile(@NonNull String serverUrl) {
        Uri serverUri = Uri.parse(serverUrl);
        String serverPath = serverUri.getPath();
        String mediaDir = null;
        String path = null;
        for (String dir : connectionState.getMediaDirs()) {
            if (serverPath.startsWith(dir)) {
                mediaDir = dir;
                break;
            }
        }
        if (mediaDir != null)
            path = serverPath.substring(mediaDir.length(), serverPath.length());
        else
            path = serverUri.getLastPathSegment();

        // Convert VFAT-unfriendly characters to "_".
        return path.replaceAll("[?<>\\\\:*|\"]", "_");
    }

    private final ISqueezeService squeezeService = new SqueezeServiceBinder();
    private class SqueezeServiceBinder extends Binder implements ISqueezeService {

        @Override
        @NonNull
        public EventBus getEventBus() {
            return mEventBus;
        }

        @Override
        public void adjustVolumeTo(Player player, int newVolume) {
            cli.sendPlayerCommand(player, "mixer volume " + Math.min(100, Math.max(0, newVolume)));
        }

        @Override
        public void adjustVolumeTo(int newVolume) {
            cli.sendActivePlayerCommand("mixer volume " + Math.min(100, Math.max(0, newVolume)));
        }

        @Override
        public void adjustVolumeBy(int delta) {
            if (delta > 0) {
                cli.sendActivePlayerCommand("mixer volume %2B" + delta);
            } else if (delta < 0) {
                cli.sendActivePlayerCommand("mixer volume " + delta);
            }
        }

        @Override
        public boolean isConnected() {
            return connectionState.isConnected();
        }

        @Override
        public boolean isConnectInProgress() {
            return connectionState.isConnectInProgress();
        }

        @Override
        public void startConnect(String hostPort, String userName, String password) {
            connectionState.startConnect(SqueezeService.this, hostPort, userName, password);
        }

        @Override
        public void disconnect() {
            if (!isConnected()) {
                return;
            }
            SqueezeService.this.disconnect();
        }

        @Override
        public void powerOn() {
            cli.sendActivePlayerCommand("power 1");
        }

        @Override
        public void powerOff() {
            cli.sendActivePlayerCommand("power 0");
        }

        @Override
        public void togglePower(Player player) {
            cli.sendPlayerCommand(player, "power");
        }

        @Override
        public void playerRename(Player player, String newName) {
            cli.sendPlayerCommand(player, "name " + Util.encode(newName));
        }

        @Override
        public void sleep(Player player, int duration) {
            cli.sendPlayerCommand(player, "sleep " + duration);
        }

        @Override
        public void syncPlayerToPlayer(@NonNull Player slave, @NonNull String masterId) {
            Player master = connectionState.getPlayer(masterId);
            cli.sendPlayerCommand(master, "sync " + Util.encode(slave.getId()));
        }

        @Override
        public void unsyncPlayer(@NonNull Player player) {
            cli.sendPlayerCommand(player, "sync -");
        }

        @Override
        public PlayerState getActivePlayerState() {
            return connectionState.getActivePlayerState();
        }

        @Override
        @Nullable
        public PlayerState getPlayerState(String playerId) {
            return connectionState.getPlayerState(playerId);
        }

        @Override
        public boolean canPowerOn() {
            PlayerState playerState = getActivePlayerState();
            return canPower() && playerState != null && !playerState.isPoweredOn();
        }

        @Override
        public boolean canPowerOff() {
            PlayerState playerState = getActivePlayerState();
            return canPower() && playerState != null && playerState.isPoweredOn();
        }

        private boolean canPower() {
            Player player = connectionState.getActivePlayer();
            return connectionState.isConnected() && player != null && player.isCanpoweroff();
        }

        @Override
        public String preferredAlbumSort() throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            return connectionState.getPreferredAlbumSort();
        }

        @Override
        public void setPreferredAlbumSort(String preferredAlbumSort) {
            if (isConnected()) {
                cli.sendCommand("pref jivealbumsort " + Util.encode(preferredAlbumSort));
            }
        }

        private String fadeInSecs() {
            return mFadeInSecs > 0 ? " " + mFadeInSecs : "";
        }

        @Override
        public boolean togglePausePlay() {
            if (!isConnected()) {
                return false;
            }

            PlayerState activePlayerState = getActivePlayerState();

            // May be null (e.g., connected to a server with no connected
            // players. TODO: Handle this better, since it's not obvious in the
            // UI.
            if (activePlayerState == null)
                return false;

            @PlayerState.PlayState String playStatus = activePlayerState.getPlayStatus();

            // May be null -- race condition when connecting to a server that
            // has a player. Squeezer knows the player exists, but has not yet
            // determined its state.
            if (playStatus == null)
                return false;

            if (playStatus.equals(PlayerState.PLAY_STATE_PLAY)) {
                // NOTE: we never send ambiguous "pause" toggle commands (without the '1')
                // because then we'd get confused when they came back in to us, not being
                // able to differentiate ours coming back on the listen channel vs. those
                // of those idiots at the dinner party messing around.
                cli.sendActivePlayerCommand("pause 1");
                return true;
            }

            if (playStatus.equals(PlayerState.PLAY_STATE_STOP)) {
                cli.sendActivePlayerCommand("play" + fadeInSecs());
                return true;
            }

            if (playStatus.equals(PlayerState.PLAY_STATE_PAUSE)) {
                cli.sendActivePlayerCommand("pause 0" + fadeInSecs());
                return true;
            }

            return true;
        }

        @Override
        public boolean play() {
            if (!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand("play" + fadeInSecs());
            return true;
        }

        @Override
        public boolean pause() {
            if(!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand("pause 1" + fadeInSecs());
            return true;
        }

        @Override
        public boolean stop() {
            if (!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand("stop");
            return true;
        }

        @Override
        public boolean nextTrack() {
            if (!isConnected() || !isPlaying()) {
                return false;
            }
            cli.sendActivePlayerCommand("button jump_fwd");
            return true;
        }

        @Override
        public boolean previousTrack() {
            if (!isConnected() || !isPlaying()) {
                return false;
            }
            cli.sendActivePlayerCommand("button jump_rew");
            return true;
        }

        @Override
        public boolean toggleShuffle() {
            if (!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand("playlist shuffle");
            return true;
        }

        @Override
        public boolean toggleRepeat() {
            if (!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand("playlist repeat");
            return true;
        }

        @Override
        public boolean playlistControl(@BaseActivity.PlaylistControlCmd String cmd, PlaylistItem playlistItem) {
            if (!isConnected()) {
                return false;
            }

            cli.sendActivePlayerCommand(
                    "playlistcontrol cmd:" + cmd + " " + playlistItem.getPlaylistParameter());
            return true;
        }

        @Override
        public boolean randomPlay(@RandomplayActivity.RandomplayType String type) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.sendActivePlayerCommand("randomplay " + type);
            return true;
        }

        /**
         * Start playing the song in the current playlist at the given index.
         *
         * @param index the index to jump to
         */
        @Override
        public boolean playlistIndex(int index) {
            if (!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand("playlist index " + index + fadeInSecs());
            return true;
        }

        @Override
        public boolean playlistRemove(int index) {
            if (!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand("playlist delete " + index);
            return true;
        }

        @Override
        public boolean playlistMove(int fromIndex, int toIndex) {
            if (!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand("playlist move " + fromIndex + " " + toIndex);
            return true;
        }

        @Override
        public boolean playlistClear() {
            if (!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand("playlist clear");
            return true;
        }

        @Override
        public boolean playlistSave(String name) {
            if (!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand("playlist save " + Util.encode(name));
            return true;
        }

        @Override
        public boolean pluginPlaylistControl(
                Plugin plugin, @PluginItemListActivity.PluginPlaylistControlCmd String cmd,
                String itemId) {
            if (!isConnected()) {
                return false;
            }
            cli.sendActivePlayerCommand(plugin.getId() + " playlist " + cmd + " item_id:" + itemId);
            return true;

        }

        private boolean isPlaying() {
            PlayerState playerState = connectionState.getActivePlayerState();
            return playerState != null && playerState.isPlaying();
        }

        @Override
        public void setActivePlayer(final Player player) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    changeActivePlayer(player);
                }
            });
        }

        @Override
        @Nullable
        public Player getActivePlayer() {
            return connectionState.getActivePlayer();
        }

        @Override
        public List<Player> getPlayers() {
            return connectionState.getPlayers();
        }

        @Override
        public PlayerState getPlayerState() {
            return connectionState.getActivePlayerState();
        }

        /**
         * @return null if there is no active player, otherwise the name of the current playlist,
         *     which may be the empty string.
         */
        @Override
        @Nullable
        public String getCurrentPlaylist() {
            PlayerState playerState = connectionState.getActivePlayerState();

            if (playerState == null)
                return null;

            return playerState.getCurrentPlaylist();
        }

        @Override
        public String getAlbumArtUrl(String artworkTrackId) throws HandshakeNotCompleteException {
            return getAbsoluteUrl(artworkTrackIdUrl(artworkTrackId));
        }

        private String artworkTrackIdUrl(String artworkTrackId) {
            return "/music/" + artworkTrackId + "/cover.jpg";
        }

        /**
         * Returns a URL to download a song.
         *
         * @param songId the song ID
         * @return The URL (as a string)
         */
        @Override
        public String getSongDownloadUrl(String songId) throws HandshakeNotCompleteException {
            return getAbsoluteUrl(songDownloadUrl(songId));
        }

        private String songDownloadUrl(String songId) {
            return "/music/" + songId + "/download";
        }

        @Override
        public String getIconUrl(String icon) throws HandshakeNotCompleteException {
            if (isRelative(icon))
                return getAbsoluteUrl(icon.startsWith("/") ? icon : '/' + icon);
            else
                return icon;
        }

        private String getAbsoluteUrl(String relativeUrl) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            Integer port = connectionState.getHttpPort();
            if (port == null || port == 0) {
                return "";
            }
            return "http://" + connectionState.getCurrentHost() + ":" + port + relativeUrl;
        }

        private boolean isRelative(String url) {
            return Uri.parse(url).isRelative();
        }

        @Override
        public boolean setSecondsElapsed(int seconds) {
            if (!isConnected()) {
                return false;
            }
            if (seconds < 0) {
                return false;
            }

            cli.sendActivePlayerCommand("time " + seconds);

            return true;
        }

        @Override
        public void preferenceChanged(String key) {
            Log.i(TAG, "Preference changed: " + key);
            cachePreferences();

            if (Preferences.KEY_NOTIFY_OF_CONNECTION.equals(key)) {
                updateOngoingNotification();
                return;
            }

            // If the server address changed then disconnect.
            if (key.startsWith(Preferences.KEY_SERVER_ADDRESS)) {
                disconnect();
                return;
            }
        }


        @Override
        public void cancelItemListRequests(Object client) {
            cli.cancelClientRequests(client);
        }

        @Override
        public void cancelSubscriptions(Object client) {
            for (Entry<ServiceCallback, ServiceCallbackList> entry : callbacks.entrySet()) {
                if (entry.getKey().getClient() == client) {
                    entry.getValue().unregister(entry.getKey());
                }
            }
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public void players() throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }

            fetchPlayers();
        }

        /* Start an async fetch of the SqueezeboxServer's albums, which are matching the given parameters */
        @Override
        public void albums(IServiceItemListCallback<Album> callback, int start, String sortOrder, String searchString, FilterItem... filters) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            List<String> parameters = new ArrayList<String>();
            parameters.add("tags:" + ALBUMTAGS);
            parameters.add("sort:" + sortOrder);
            if (searchString != null && searchString.length() > 0) {
                parameters.add("search:" + searchString);
            }
            for (FilterItem filter : filters)
                if (filter != null)
                    parameters.add(filter.getFilterParameter());
            cli.requestItems("albums", start, parameters, callback);
        }


        /* Start an async fetch of the SqueezeboxServer's artists */
        @Override
        public void artists(IServiceItemListCallback<Artist> callback, int start, String searchString, FilterItem... filters) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            List<String> parameters = new ArrayList<String>();
            if (searchString != null && searchString.length() > 0) {
                parameters.add("search:" + searchString);
            }
            for (FilterItem filter : filters)
                if (filter != null)
                    parameters.add(filter.getFilterParameter());
            cli.requestItems("artists", start, parameters, callback);
        }

        /* Start an async fetch of the SqueezeboxServer's years */
        @Override
        public void years(int start, IServiceItemListCallback<Year> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestItems("years", start, callback);
        }

        /* Start an async fetch of the SqueezeboxServer's genres */
        @Override
        public void genres(int start, String searchString, IServiceItemListCallback<Genre> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            List<String> parameters = new ArrayList<String>();
            if (searchString != null && searchString.length() > 0) {
                parameters.add("search:" + searchString);
            }
            cli.requestItems("genres", start, parameters, callback);
        }

        /**
         * Starts an async fetch of the contents of a SqueezerboxServer's music
         * folders in the given folderId.
         * <p>
         * folderId may be null, in which case the contents of the root music
         * folder are returned.
         * <p>
         * Results are returned through the given callback.
         *
         * @param start Where in the list of folders to start.
         * @param musicFolderItem The folder to view.
         * @param callback Results will be returned through this
         */
        @Override
        public void musicFolders(int start, MusicFolderItem musicFolderItem, IServiceItemListCallback<MusicFolderItem> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }

            List<String> parameters = new ArrayList<String>();

            parameters.add("tags:u");//TODO only available from version 7.6 so instead keep track of path
            if (musicFolderItem != null) {
                parameters.add(musicFolderItem.getFilterParameter());
            }

            cli.requestItems("musicfolder", start, parameters, callback);
        }

        /* Start an async fetch of the SqueezeboxServer's songs */
        @Override
        public void songs(IServiceItemListCallback<Song> callback, int start, String sortOrder, String searchString, FilterItem... filters) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            List<String> parameters = new ArrayList<String>();
            parameters.add("tags:" + SONGTAGS);
            parameters.add("sort:" + sortOrder);
            if (searchString != null && searchString.length() > 0) {
                parameters.add("search:" + searchString);
            }
            for (FilterItem filter : filters)
                if (filter != null)
                    parameters.add(filter.getFilterParameter());
            cli.requestItems("songs", start, parameters, callback);
        }

        /* Start an async fetch of the SqueezeboxServer's current playlist */
        @Override
        public void currentPlaylist(int start, IServiceItemListCallback<Song> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestPlayerItems("status", start, Arrays.asList("tags:" + SONGTAGS), callback);
        }

        /* Start an async fetch of the songs of the supplied playlist */
        @Override
        public void playlistSongs(int start, Playlist playlist, IServiceItemListCallback<Song> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestItems("playlists tracks", start,
                    Arrays.asList(playlist.getFilterParameter(), "tags:" + SONGTAGS), callback);
        }

        /* Start an async fetch of the SqueezeboxServer's playlists */
        @Override
        public void playlists(int start, IServiceItemListCallback<Playlist> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestItems("playlists", start, callback);
        }

        @Override
        public boolean playlistsDelete(Playlist playlist) {
            if (!isConnected()) {
                return false;
            }
            cli.sendCommand("playlists delete " + playlist.getFilterParameter());
            return true;
        }

        @Override
        public boolean playlistsMove(Playlist playlist, int index, int toindex) {
            if (!isConnected()) {
                return false;
            }
            cli.sendCommand("playlists edit cmd:move " + playlist.getFilterParameter()
                    + " index:" + index + " toindex:" + toindex);
            return true;
        }

        @Override
        public boolean playlistsNew(String name) {
            if (!isConnected()) {
                return false;
            }
            cli.sendCommand("playlists new name:" + Util.encode(name));
            return true;
        }

        @Override
        public boolean playlistsRemove(Playlist playlist, int index) {
            if (!isConnected()) {
                return false;
            }
            cli.sendCommand("playlists edit cmd:delete " + playlist.getFilterParameter() + " index:"
                    + index);
            return true;
        }

        @Override
        public boolean playlistsRename(Playlist playlist, String newname) {
            if (!isConnected()) {
                return false;
            }
            cli.sendCommand(
                    "playlists rename " + playlist.getFilterParameter() + " dry_run:1 newname:"
                            + Util.encode(newname));
            return true;
        }

        /* Start an asynchronous search of the SqueezeboxServer's library */
        @Override
        public void search(int start, String searchString, IServiceItemListCallback itemListCallback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }

            AlbumViewDialog.AlbumsSortOrder albumSortOrder = AlbumViewDialog.AlbumsSortOrder
                    .valueOf(
                            preferredAlbumSort());

            artists(itemListCallback, start, searchString);
            albums(itemListCallback, start, albumSortOrder.name().replace("__", ""), searchString);
            genres(start, searchString, itemListCallback);
            songs(itemListCallback, start, SongViewDialog.SongsSortOrder.title.name(), searchString);
        }

        /* Start an asynchronous fetch of the squeezeservers radio type plugins */
        @Override
        public void radios(int start, IServiceItemListCallback<Plugin> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestItems("radios", start, callback);
        }

        /* Start an asynchronous fetch of the squeezeservers radio application plugins */
        @Override
        public void apps(int start, IServiceItemListCallback<Plugin> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestItems("apps", start, callback);
        }


        /* Start an asynchronous fetch of the squeezeservers items of the given type */
        @Override
        public void pluginItems(int start, Plugin plugin, PluginItem parent, String search, IServiceItemListCallback<PluginItem> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            List<String> parameters = new ArrayList<String>();
            if (parent != null) {
                parameters.add("item_id:" + parent.getId());
            }
            if (search != null && search.length() > 0) {
                parameters.add("search:" + search);
            }
            cli.requestPlayerItems(plugin.getId() + " items", start, parameters, callback);
        }

        @Override
        public void downloadItem(FilterItem item) throws HandshakeNotCompleteException {
            if (item instanceof Song) {
                Song song = (Song) item;
                if (!song.isRemote()) {
                    downloadSong(song.getId(), song.getName(), song.getUrl());
                }
            } else if (item instanceof Playlist) {
                playlistSongs(-1, (Playlist) item, songDownloadCallback);
            } else if (item instanceof MusicFolderItem) {
                MusicFolderItem musicFolderItem = (MusicFolderItem) item;
                if ("track".equals(musicFolderItem.getType())) {
                    String url = musicFolderItem.getUrl();
                    if (url != null) {
                        downloadSong(item.getId(), musicFolderItem.getName(), url);
                    }
                } else if ("folder".equals(musicFolderItem.getType())) {
                    musicFolders(-1, musicFolderItem, musicFolderDownloadCallback);
                }
            } else if (item != null) {
                songs(songDownloadCallback, -1, SongViewDialog.SongsSortOrder.title.name(), null, item);
            }
        }
    }

    /**
     * Calculate and set player subscription states every time a client of the bus
     * un/registers.
     * <p/>
     * For example, this ensures that if a new client subscribes and needs real
     * time updates, the player subscription states will be updated accordingly.
     */
    class EventBus extends de.greenrobot.event.EventBus {

        @Override
        public void register(Object subscriber) {
            super.register(subscriber);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public void register(Object subscriber, int priority) {
            super.register(subscriber, priority);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public void registerSticky(Object subscriber) {
            super.registerSticky(subscriber);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public void registerSticky(Object subscriber, int priority) {
            super.registerSticky(subscriber, priority);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public synchronized void unregister(Object subscriber) {
            super.unregister(subscriber);
            updateAllPlayerSubscriptionStates();
        }
    }
}
