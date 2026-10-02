package com.jagrosh.jmusicbot.commands.owner;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.OwnerCommand;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

/**
 * Owner-only live YouTube load + stream probe (does not join voice or affect the queue).
 */
public class YoutubeTestCmd extends OwnerCommand
{
    private static final String DEFAULT_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    private final Bot bot;

    public YoutubeTestCmd(Bot bot)
    {
        this.bot = bot;
        this.name = "youtubetest";
        this.help = "tests YouTube load + streaming (owner only)";
        this.arguments = "[youtube url]";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.guildOnly = false;
    }

    @Override
    protected void execute(CommandEvent event)
    {
        String raw = event.getArgs();
        final String url = (raw == null || raw.isBlank()) ? DEFAULT_URL : raw.trim();

        event.reply("YouTube probe starting for `" + url + "` — this may take up to 45s…");

        bot.getPlayerManager().loadItem(url, new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack track)
            {
                probeStream(event, track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist)
            {
                AudioTrack selected = playlist.getSelectedTrack();
                if(selected == null && !playlist.getTracks().isEmpty())
                    selected = playlist.getTracks().get(0);
                if(selected == null)
                {
                    event.replyError("Load succeeded but playlist had no tracks.");
                    return;
                }
                probeStream(event, selected);
            }

            @Override
            public void noMatches()
            {
                event.replyError("YouTube probe: **noMatches** for `" + url + "`");
            }

            @Override
            public void loadFailed(FriendlyException exception)
            {
                event.replyError("YouTube probe: **load failed** — " + exception.getMessage());
            }
        });
    }

    private void probeStream(CommandEvent event, AudioTrack track)
    {
        var player = bot.getPlayerManager().createPlayer();
        player.playTrack(track);

        bot.getThreadpool().schedule(() ->
        {
            int frames = 0;
            FriendlyException failure = null;
            for(int i = 0; i < 200; i++)
            {
                if(player.getPlayingTrack() == null && i > 20)
                    break;
                try
                {
                    var frame = player.provide();
                    if(frame != null)
                        frames++;
                }
                catch(FriendlyException ex)
                {
                    failure = ex;
                    break;
                }
                catch(Exception ex)
                {
                    failure = new FriendlyException(ex.getMessage(), FriendlyException.Severity.COMMON, ex);
                    break;
                }
                try
                {
                    Thread.sleep(5);
                }
                catch(InterruptedException ignored)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            player.destroy();

            if(failure != null)
            {
                event.replyError("Loaded **" + track.getInfo().title + "** but streaming failed:\n```\n"
                        + failure.getMessage() + "\n```");
                return;
            }

            if(frames == 0)
            {
                event.replyError("Loaded **" + track.getInfo().title + "** but produced **0 audio frames** (playback blocked).");
                return;
            }

            event.replySuccess("YouTube OK — loaded and streamed **" + track.getInfo().title + "** (" + frames + " frames).");
        }, 2, java.util.concurrent.TimeUnit.SECONDS);
    }
}
