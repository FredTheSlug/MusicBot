/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
 *
 * Modified 2026 by Fred (https://github.com/FredTheSlug/MusicBot).
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
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.playlist.PlaylistFile;
import com.jagrosh.jmusicbot.playlist.PlaylistPlayer;
import com.jagrosh.jmusicbot.playlist.PlaylistStore;
import com.jagrosh.jmusicbot.playlist.PlaylistStore.Entry;
import com.jagrosh.jmusicbot.queue.AbstractQueue;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import java.nio.ByteBuffer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class AudioHandler extends AudioEventAdapter implements AudioSendHandler 
{
    public final static String PLAY_EMOJI  = "\u25B6"; // ▶
    public final static String PAUSE_EMOJI = "\u23F8"; // ⏸
    public final static String STOP_EMOJI  = "\u23F9"; // ⏹


    private final List<AudioTrack> defaultQueue = new LinkedList<>();
    private final Set<String> votes = new HashSet<>();
    private final LinkedList<QueuedTrack> history = new LinkedList<>();
    private static final int DEFAULT_HISTORY_SIZE = 50;
    
    private final PlayerManager manager;
    private final AudioPlayer audioPlayer;
    private final long guildId;
    /** True while stopAndClear() stops the track, so that stop never turns into autoplay. */
    private volatile boolean stopping = false;
    /** Counts stops, so a stop while autoplay is still loading cancels its pick. */
    private final AtomicInteger stops = new AtomicInteger();
    
    private AudioFrame lastFrame;
    private AbstractQueue<QueuedTrack> queue;
    private String lastPlaybackError;

    protected AudioHandler(PlayerManager manager, Guild guild, AudioPlayer player)
    {
        this.manager = manager;
        this.audioPlayer = player;
        this.guildId = guild.getIdLong();

        this.setQueueType(manager.getBot().getSettingsManager().getSettings(guildId).getQueueType());
    }

    public void setQueueType(QueueType type)
    {
        queue = type.createInstance(queue);
    }

    public int addTrackToFront(QueuedTrack qtrack)
    {
        if(audioPlayer.getPlayingTrack()==null)
        {
            audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else
        {
            queue.addAt(0, qtrack);
            broadcastWeb("queue");
            return 0;
        }
    }
    
    public int addTrack(QueuedTrack qtrack)
    {
        if(audioPlayer.getPlayingTrack()==null)
        {
            audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else
        {
            int pos = queue.add(qtrack);
            broadcastWeb("queue");
            return pos;
        }
    }
    
    public AbstractQueue<QueuedTrack> getQueue()
    {
        return queue;
    }
    
    public void stopAndClear()
    {
        queue.clear();
        defaultQueue.clear();
        stops.incrementAndGet();
        stopping = true;
        try
        {
            // The track-end event fires synchronously inside stopTrack().
            audioPlayer.stopTrack();
        }
        finally
        {
            stopping = false;
        }
        broadcastWeb("player");
        broadcastWeb("queue");
    }

    /** Removes all upcoming tracks; does not stop the track currently playing. */
    public void clearQueue()
    {
        queue.clear();
        broadcastWeb("queue");
    }
    
    public boolean isMusicPlaying(JDA jda)
    {
        return guild(jda).getSelfMember().getVoiceState().inAudioChannel() && audioPlayer.getPlayingTrack()!=null;
    }
    
    public Set<String> getVotes()
    {
        return votes;
    }
    
    public AudioPlayer getPlayer()
    {
        return audioPlayer;
    }
    
    public RequestMetadata getRequestMetadata()
    {
        if(audioPlayer.getPlayingTrack() == null)
            return RequestMetadata.EMPTY;
        RequestMetadata rm = audioPlayer.getPlayingTrack().getUserData(RequestMetadata.class);
        return rm == null ? RequestMetadata.EMPTY : rm;
    }
    
    public boolean playFromDefault()
    {
        if(!defaultQueue.isEmpty())
        {
            audioPlayer.playTrack(defaultQueue.remove(0));
            return true;
        }
        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        if(settings==null || settings.getDefaultPlaylist()==null)
            return false;

        try
        {
            Guild guild = manager.getBot().getJDA().getGuildById(guildId);
            PlaylistStore store = manager.getBot().getPlaylistStore();
            Entry entry = store.find(settings.getDefaultPlaylist()).orElse(null);
            PlaylistFile pl = entry == null ? null : store.get(entry.getName(), entry.getFolder()).orElse(null);
            if(guild==null || pl==null || !PlaylistPlayer.hasPlayable(pl))
                return false;
            manager.getBot().getPlaylistPlayer().load(guild, entry.getName(), entry.getFolder(), PlaylistPlayer.ALL, null, (at) ->
            {
                if(audioPlayer.getPlayingTrack()==null)
                    audioPlayer.playTrack(at);
                else
                    defaultQueue.add(at);
            }, (result) ->
            {
                if(result.getLoaded()==0 && !manager.getBot().getConfig().getStay())
                    manager.getBot().closeAudioConnection(guildId);
            });
            return true;
        }
        catch(RuntimeException e) // this runs inside a track-end event: never let a bad playlist escape it
        {
            LoggerFactory.getLogger("AudioHandler").warn("Could not play the default playlist '{}'", settings.getDefaultPlaylist(), e);
            return false;
        }
    }
    
    // Audio Events
    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) 
    {
        if(endReason != AudioTrackEndReason.FINISHED)
        {
            LoggerFactory.getLogger("AudioHandler").warn("Track ended early ({}): {} — {}{}",
                    endReason, track.getInfo().title, track.getIdentifier(),
                    lastPlaybackError == null ? "" : " (" + lastPlaybackError + ")");
            lastPlaybackError = null;
        }

        RepeatMode repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();
        // if the track ended normally, and we're in repeat mode, re-add it to the queue
        if(endReason==AudioTrackEndReason.FINISHED && repeatMode != RepeatMode.OFF)
        {
            QueuedTrack clone = new QueuedTrack(track.makeClone(), track.getUserData(RequestMetadata.class));
            if(repeatMode == RepeatMode.ALL)
                queue.add(clone);
            else
                queue.addAt(0, clone);
        }
        
        if(queue.isEmpty())
        {
            if(!playFromDefault() && !startAutoplay(track, endReason, repeatMode))
                endPlayback();
        }
        else
        {
            maybeShuffleUpcomingHead();
            QueuedTrack qt = queue.pull();
            player.playTrack(qt.getTrack());
        }
        broadcastWeb("player");
        broadcastWeb("queue");
    }

    /** Nothing left to play: clear the now-playing status and leave voice unless told to stay. */
    private void endPlayback()
    {
        manager.getBot().getNowplayingHandler().onTrackUpdate(null);
        if(!manager.getBot().getConfig().getStay())
            manager.getBot().closeAudioConnection(guildId);
        // unpause, in the case when the player was paused and the track has been skipped.
        // this is to prevent the player being paused next time it's being used.
        audioPlayer.setPaused(false);
    }

    /**
     * When autoplay is on and the queue just ran out (a song finished or was skipped), starts
     * loading a related song. Returns false when autoplay doesn't apply; when it returns true the
     * load ends playback itself if it finds nothing.
     */
    private boolean startAutoplay(AudioTrack ended, AudioTrackEndReason reason, RepeatMode repeatMode)
    {
        if(stopping || repeatMode != RepeatMode.OFF || !Autoplay.continuesAfter(reason))
            return false;
        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        Guild guild = manager.getBot().getJDA().getGuildById(guildId);
        if(settings == null || !settings.getAutoplay() || guild == null)
            return false;
        // Nobody listening: don't stream radio to an empty channel.
        if(AloneInVoiceHandler.isAlone(guild))
            return false;
        BooleanSupplier wanted = Autoplay.stillWanted(stops);
        Set<String> recent = new HashSet<>();
        for(QueuedTrack played : history)
            recent.add(played.getTrack().getIdentifier());
        Autoplay.queueRelated(manager, guild, ended, recent,
                next ->
                {
                    if(wanted.getAsBoolean())
                        addTrack(new QueuedTrack(next, Autoplay.METADATA));
                },
                () ->
                {
                    // Something else may have started meanwhile (someone queued a song), or a stop
                    // already ended playback.
                    if(wanted.getAsBoolean() && audioPlayer.getPlayingTrack() == null && queue.isEmpty())
                    {
                        endPlayback();
                        broadcastWeb("player");
                        broadcastWeb("queue");
                    }
                });
        return true;
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        lastPlaybackError = exception.getMessage();
        LoggerFactory.getLogger("AudioHandler").error("Playback failed for {} ({}): {}",
                track.getInfo().title, track.getIdentifier(), exception.getMessage(), exception);
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) 
    {
        lastPlaybackError = null;
        votes.clear();
        addToHistory(track);
        try
        {
            manager.getBot().getPlayCountStore().recordPlay(guildId, track);
        }
        catch (Exception ignored) {}
        manager.getBot().getNowplayingHandler().onTrackUpdate(track);
        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        if(settings != null && settings.getSponsorBlock())
            SponsorBlock.apply(track, () -> broadcastWeb("player"));
        broadcastWeb("player");
        broadcastWeb("history");
        broadcastWeb("frequent");
    }

    @Override
    public void onPlayerPause(AudioPlayer player)
    {
        broadcastWeb("player");
    }

    @Override
    public void onPlayerResume(AudioPlayer player)
    {
        broadcastWeb("player");
    }

    private void broadcastWeb(String type)
    {
        try
        {
            com.jagrosh.jmusicbot.web.WebServer ws = manager.getBot().getWebServer();
            if (ws != null)
                ws.broadcastUpdate(String.valueOf(guildId), type);
        }
        catch (Exception ignored) {}
    }

    /**
     * When shuffle mode is on, pick a random waiting track (not the current "next" at index 0)
     * and move it to the front so {@link AbstractQueue#pull()} plays it.
     */
    private void maybeShuffleUpcomingHead()
    {
        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        if (settings == null || !settings.getShuffleMode())
            return;
        int n = queue.size();
        if (n < 2)
            return;
        int r;
        do
        {
            r = ThreadLocalRandom.current().nextInt(n);
        }
        while (r == 0);
        queue.moveItem(r, 0);
    }

    private void addToHistory(AudioTrack track)
    {
        int maxSize = DEFAULT_HISTORY_SIZE;
        try
        {
            maxSize = manager.getBot().getConfig().getMaxHistorySize();
        }
        catch (Exception ignored) {}
        if (maxSize <= 0) return;
        QueuedTrack qt = new QueuedTrack(track.makeClone(), track.getUserData(RequestMetadata.class));
        history.addFirst(qt);
        while (history.size() > maxSize)
            history.removeLast();
    }

    public List<QueuedTrack> getHistory()
    {
        return new LinkedList<>(history);
    }

    public void clearHistory()
    {
        history.clear();
        broadcastWeb("history");
    }

    
    // Formatting
    public MessageCreateData getNowPlaying(JDA jda)
    {
        if(isMusicPlaying(jda))
        {
            Guild guild = guild(jda);
            AudioTrack track = audioPlayer.getPlayingTrack();
            MessageCreateBuilder mb = new MessageCreateBuilder();
            mb.setContent(FormatUtil.filter(manager.getBot().getConfig().getSuccess()+" **Now Playing in "+guild.getSelfMember().getVoiceState().getChannel().getAsMention()+"...**"));
            EmbedBuilder eb = new EmbedBuilder();
            eb.setColor(guild.getSelfMember().getColor());
            RequestMetadata rm = getRequestMetadata();
            if(rm.getOwner() != 0L)
            {
                User u = guild.getJDA().getUserById(rm.user.id);
                if(u==null)
                    eb.setAuthor(FormatUtil.formatUsername(rm.user), null, rm.user.avatar);
                else
                    eb.setAuthor(FormatUtil.formatUsername(u), null, u.getEffectiveAvatarUrl());
            }

            try 
            {
                eb.setTitle(track.getInfo().title, track.getInfo().uri);
            }
            catch(Exception e) 
            {
                eb.setTitle(track.getInfo().title);
            }

            if("youtube".equals(track.getSourceManager().getSourceName()) && manager.getBot().getConfig().useNPImages())
            {
                eb.setThumbnail("https://img.youtube.com/vi/"+track.getIdentifier()+"/mqdefault.jpg");
            }
            
            if(track.getInfo().author != null && !track.getInfo().author.isEmpty())
                eb.setFooter("Source: " + track.getInfo().author, null);

            double progress = (double)audioPlayer.getPlayingTrack().getPosition()/track.getDuration();
            eb.setDescription(getStatusEmoji()
                    + " "+FormatUtil.progressBar(progress)
                    + " `[" + TimeUtil.formatTime(track.getPosition()) + "/" + TimeUtil.formatTime(track.getDuration()) + "]` "
                    + FormatUtil.volumeIcon(audioPlayer.getVolume()));
            
            return mb.setEmbeds(eb.build()).build();
        }
        else return null;
    }
    
    public MessageCreateData getNoMusicPlaying(JDA jda)
    {
        Guild guild = guild(jda);
        return new MessageCreateBuilder()
                .setContent(FormatUtil.filter(manager.getBot().getConfig().getSuccess()+" **Now Playing...**"))
                .setEmbeds(new EmbedBuilder()
                .setTitle("No music playing")
                .setDescription(STOP_EMOJI+" "+FormatUtil.progressBar(-1)+" "+FormatUtil.volumeIcon(audioPlayer.getVolume()))
                .setColor(guild.getSelfMember().getColor())
                .build()).build();
    }

    public String getStatusEmoji()
    {
        return audioPlayer.isPaused() ? PAUSE_EMOJI : PLAY_EMOJI;
    }
    
    // Audio Send Handler methods
    /*@Override
    public boolean canProvide() 
    {
        if (lastFrame == null)
            lastFrame = audioPlayer.provide();

        return lastFrame != null;
    }

    @Override
    public byte[] provide20MsAudio() 
    {
        if (lastFrame == null) 
            lastFrame = audioPlayer.provide();

        byte[] data = lastFrame != null ? lastFrame.getData() : null;
        lastFrame = null;

        return data;
    }*/
    
    @Override
    public boolean canProvide() 
    {
        if(lastFrame == null)
            lastFrame = audioPlayer.provide();
        return lastFrame != null;
    }

    @Override
    public ByteBuffer provide20MsAudio() 
    {
        if(lastFrame == null)
            return null;
        ByteBuffer buffer = ByteBuffer.wrap(lastFrame.getData());
        lastFrame = null;
        return buffer;
    }

    @Override
    public boolean isOpus() 
    {
        return true;
    }
    
    
    // Private methods
    private Guild guild(JDA jda)
    {
        return jda.getGuildById(guildId);
    }
}
