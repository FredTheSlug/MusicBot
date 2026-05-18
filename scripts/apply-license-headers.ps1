# One-off helper: Apache 2.0 headers for FredTheSlug/MusicBot fork
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

$apacheBoilerplate = @'
/*
 * Copyright 2026 Fred (https://github.com/FredTheSlug)
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
'@

$modifiedLine = " *`n * Modified 2026 by Fred (https://github.com/FredTheSlug/MusicBot).`n"

$newFiles = @(
    "src/main/java/com/jagrosh/jmusicbot/audio/SpotifyPlayback.java",
    "src/main/java/com/jagrosh/jmusicbot/audio/SpotifyResolveResult.java",
    "src/main/java/com/jagrosh/jmusicbot/audio/SpotifyResolver.java",
    "src/main/java/com/jagrosh/jmusicbot/utils/SpotifyUtil.java",
    "src/main/java/com/jagrosh/jmusicbot/utils/SoundCloudUtil.java",
    "src/main/java/com/jagrosh/jmusicbot/web/LyricsFetch.java",
    "src/main/java/com/jagrosh/jmusicbot/web/WebServer.java",
    "src/test/java/com/jagrosh/jmusicbot/SpotifyResolverTest.java"
)

$modifiedFiles = @(
    "src/main/java/com/jagrosh/jmusicbot/Bot.java",
    "src/main/java/com/jagrosh/jmusicbot/BotConfig.java",
    "src/main/java/com/jagrosh/jmusicbot/JMusicBot.java",
    "src/main/java/com/jagrosh/jmusicbot/Listener.java",
    "src/main/java/com/jagrosh/jmusicbot/audio/AudioHandler.java",
    "src/main/java/com/jagrosh/jmusicbot/audio/NowplayingHandler.java",
    "src/main/java/com/jagrosh/jmusicbot/audio/RequestMetadata.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/MusicCommand.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/admin/SettcCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/admin/SetvcCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/dj/MoveTrackCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/dj/PlaynextCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/general/SettingsCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/music/NowplayingCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/music/PlayCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/music/QueueCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/music/RemoveCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/owner/DebugCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/commands/owner/EvalCmd.java",
    "src/main/java/com/jagrosh/jmusicbot/gui/ConsolePanel.java",
    "src/main/java/com/jagrosh/jmusicbot/playlist/PlaylistLoader.java",
    "src/main/java/com/jagrosh/jmusicbot/queue/AbstractQueue.java",
    "src/main/java/com/jagrosh/jmusicbot/settings/Settings.java",
    "src/main/java/com/jagrosh/jmusicbot/settings/SettingsManager.java",
    "src/main/java/com/jagrosh/jmusicbot/utils/FormatUtil.java"
)

function Prepend-Header($path, $header) {
    $full = Join-Path $root $path
    $text = [IO.File]::ReadAllText($full)
    if ($text -match "Licensed under the Apache License") { return }
    if (-not $text.StartsWith("package ") -and -not $text.StartsWith("/*")) {
        [IO.File]::WriteAllText($full, $header + "`n" + $text)
        return
    }
    if ($text.StartsWith("package ")) {
        [IO.File]::WriteAllText($full, $header + "`n" + $text)
    }
}

function Add-Modified-Notice($path) {
    $full = Join-Path $root $path
    $text = [IO.File]::ReadAllText($full)
    if ($text -match "Modified 2026 by Fred") { return }
    if ($text -match "(?m)^ \* Copyright \d+ John Grosh.*$") {
        $text = [regex]::Replace($text, "(?m)^( \* Copyright \d+ John Grosh[^\r\n]*\r?\n)", "`${1}$modifiedLine", 1)
        [IO.File]::WriteAllText($full, $text)
        return
    }
    if ($path -like "*MoveTrackCmd.java") {
        $header = @'
/*
 * Copyright 2018 John Grosh (jagrosh).
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
'@
        [IO.File]::WriteAllText($full, $header + "`n" + $text)
    }
}

foreach ($f in $newFiles) { Prepend-Header $f $apacheBoilerplate }
foreach ($f in $modifiedFiles) { Add-Modified-Notice $f }

$scPath = Join-Path $root "src/main/java/com/sedmelluq/discord/lavaplayer/source/soundcloud/SoundCloudClientIdTracker.java"
$scHeader = @'
/*
 * Copyright 2019 Sedmelluq and Lavaplayer contributors.
 *
 * Modified 2026 by Fred (https://github.com/FredTheSlug/MusicBot) for SoundCloud HTML/API drift.
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
 *
 * Upstream picked a single webpack URL (last among the first N matches). That breaks when SoundCloud
 * adds/removes chunks or when the client id moves. We scan multiple candidate bundles (newest first)
 * and fall back to the web client's api id embedded in {@code window.__sc_hydration}.
 *
 * Original class excluded from the shaded jar via pom.xml.
 */
'@
$scText = [IO.File]::ReadAllText($scPath)
if ($scText -notmatch "Licensed under the Apache License") {
    $scText = $scText -replace "(?s)^/\*.*?\*/\s*", ""
    [IO.File]::WriteAllText($scPath, $scHeader + "`n" + $scText.TrimStart())
}

Write-Host "Done."
