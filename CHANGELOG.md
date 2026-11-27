# Changelog

Write each entry the way you would advertise it to a viewer on the sofa:

- Lead with what the viewer gets, in their words. Say "Torrentio without Real-Debrid", not "P2P sources".
- Start each entry with a short **bold** headline, then one or two short sentences. Keep spotlight descriptions under 25 words before any settings path.
- Use the names that Alder shows on the screen, in **bold**. Say where to find a setting.
- Put each entry under one heading: `### New`, `### Easier to use` or `### Fixes`, in that order, most important first.
- Group small fixes by where the viewer sees them: "Small fixes for Google TV: …".
- Mark at most five entries to show them first in **What's new**. Use `<!-- Spotlight -->` for a text highlight or
  `<!-- Spotlight: whats-new/name.webp -->` for a full 1920×1080 TV screenshot saved as WebP in
  `app/src/main/assets/whats-new/`. Add `<!-- Detail: whats-new/name.webp -->` for a smaller illustration or crop.
  Use `<!-- Player: VLC whats-new/icon.webp -->` to show an example app for external playback.
  Put a bold settings path at the end of the entry to show it on its own line. Remove old pictures after a release.

Put changes that are not released under `## Unreleased`. Before each release, read all of `## Unreleased` again and rewrite
each entry that does not follow these rules. Then rename that heading to the version number.

<!-- ChadFlix: give each release its own section here, named as versionName. Do not rename Unreleased: it is Alder's. -->
## 1.4-chadflix.4

### New
<!-- Spotlight -->
- **Trailer previews on Home.** Rest on a movie or show and its trailer plays inside the card, with sound. Move on and the poster is back at once. **Settings → Picture & Sound → Trailer Previews**
<!-- Spotlight -->
- **A tidier Settings.** Each section's name sits above its options. Rarely changed options moved to **Advanced**. Home options are in **Customize Home → General**, and **Profile & Add-ons** holds your profiles and add-ons.
<!-- Spotlight -->
- **Like and Watchlist on the title page.** The title page shows **Like** and **Watchlist** buttons, and the cast with their portraits.
- **Directors & Cast in a pop-up.** In **Search**, **Directors & Cast** opens over the posters, two rows for directors and two for cast.

### Easier to use
<!-- Spotlight -->
- **Smoother scrolling.** Moving up and down the rows is smoother on SHIELD. Holding a direction moves through the posters without the title and story flashing past, and the card you stop on is always fully on screen.
- **Calmer title page.** The title page fades in once, without the cast or buttons jumping, and **Resume** is selected first.
- **Better Back in Search.** **Back** from filtered posters reopens the menu you used. **Back** from the top row goes to the top bar.
- **One font everywhere.** Every screen uses the same typeface.
- **Even buttons.** Buttons in the same column or row are the same size.
- **Clearer names in Settings.** Labels and values are in Title Case, and **Highest Resolution**, **Subtitle Size** and **Blurred Background** have clearer names.

### Fixes
- **Small fixes for Settings:** **More Filters**, which only showed a message, is gone. **Up** from the first option goes back to its section.
- **Small fixes for the title page:** long show pages fit without scrolling, and the art's shade is only as dark as it needs to be.

## 1.4-chadflix.3

### New
<!-- Spotlight -->
- **A new Search.** The movie or show you rest on fills the top with its logo, ratings, story and cast. The filters sit in one row above the posters, and the posters move one row at a time.
<!-- Spotlight -->
- **Keyboard and Voice in the top bar.** Open **Search** and the magnifier becomes **Keyboard** and **Voice**. Search starts on **Voice**.
<!-- Spotlight -->
- **Sort in one place.** Choose **Popular**, **Top Rated**, **Newest** and more from **Sort** at the right of the filter row.
- **A pill that glides.** Moving between **Movies**, **Shows**, **Services** and **My Lists**, the white pill stretches to the next page and settles.

### Easier to use
- **Simpler filters.** The filters are **Genre**, **Year**, **Directors & Cast**, **Studio** and **Language**. **Clear** and **Country** are gone.
- **Faster to Search.** Press **Left** onto the magnifier and Search opens as quickly as any other page.
- **Tidier top bar.** No grey pill is left on the page you came from.
- **Easier to read.** Titles keep clear of the top bar, and cast photos show whole faces.
- **Even buttons.** Filter chips, **Sort** and the title page's buttons fit their words.

### Fixes
- **Small fixes for Search:** opening Search no longer lands in **Directors & Cast**. **Back** in **Custom Range** returns to **Year**, and **All** shows its tick.
- **Settings:** **Audio Language** is back in **Picture & Sound**.

## 1.4-chadflix.2

### New
<!-- Spotlight -->
- **Browse everything in Search.** Open **Search** and choose **People**, **Genre**, **Year**, **Studio**, **Language** or **Country** on the left. The movies or shows that match fill the screen.
- **Your way through Search.** Pick **Popular**, **Top Rated**, **Newest** or **More** above the posters. In **Genre**, set **Match** to **All** or **Any**. Rest on a poster to see its story, ratings and cast. **Voice Search** and **Keyboard Search** sit above the filters.
<!-- Spotlight -->
- **Watchlists.** Make your own lists in **My Lists**. Select **New Watchlist** and give it a name. To add a movie or show, hold **OK** on it and select **Save To Watchlist**. Each profile has its own lists on this TV.
<!-- Spotlight -->
- **New title page.** Movies and shows show the logo, details, cast and a column of buttons: **Play**, **More Episodes** and **More Like This**.
<!-- Spotlight -->
- **More Episodes.** On a show, select **More Episodes**. The seasons are on the left. Each episode shows its picture, name and story on the right.

### Easier to use
- **Simpler My Lists.** **My Lists** shows your watchlists, **Saved Movies** and **Saved Shows** on one screen.
- **Filter your saved titles.** The first card in **Saved Movies** and **Saved Shows** is **Showing**. Press **OK** on it to choose a genre, a year or the order.
- **Cleaner posters.** Posters in **My Lists** and **Continue Watching** no longer show a rating. The title page still does.
- **Settings with OK.** In **Settings**, press **OK** or **Right** to change a value. Press **Left** to go back to the menu.
- **Smoother top bar.** **Left** and **Right** move to the next tab you can see, and from the last tab to **Settings** and back.

## 1.4-chadflix.1

### New
<!-- Spotlight -->
- **Alder 1.4 inside.** ChadFlix now has the changes from Alder 1.4. The ChadFlix look, rows and player menus stay the same.
<!-- Spotlight -->
- **Titles in your language.** Titles, stories, posters and episode names follow the app language, not only English. Select **Settings → Language → App language**.
<!-- Spotlight -->
- **Fallback audio language.** With **My language first**, ChadFlix plays your language, then a second language, then English. To choose the second language or turn it off, select **Settings → Picture & Sound → Fallback audio language**.
- **New episode badges.** Choose how the **+1** on a series poster counts, or turn it off. Select **Settings → Profile → New episode badges**.

### Easier to use
- **The sharpest picture first.** When you press **Play**, ChadFlix picks the sharpest copy your TV can show. Your language and quality choices still come first. **Add-on's order** is now called **Any release**.
- **Faster start.** **Continue Watching** shows at once when ChadFlix opens, and background pictures load faster.
- **More in My Lists and Discover.** Above the posters, see the year, genre, seasons and ratings of the title you select. Its art shows behind its name.
- **A new What's New.** What's New fills the screen and shows the highlights first.

### Fixes
- **Full row names.** **More like** rows show the whole movie name.

## 1.3.1-chadflix.2

### New
<!-- Spotlight -->
- **Updates in ChadFlix.** ChadFlix finds new versions. It asks you before it installs them. To find a new version yourself, select **Settings → About → Updates**.
<!-- Spotlight -->
- **Audio & Subtitles menu.** In the player, move to **Audio & Subtitles** and wait a short time. A menu opens with the columns **Audio** and **Subtitles**. Each column shows the track in use, the best English track and the best track in the original language of the title. Each language shows once. Select **More** to see all tracks, and the codec and channels of each audio track. Subtitles from add-ons join the list when they arrive.
- **Appearance.** The **Audio & Subtitles** menu has a third column, **Appearance**, next to **Subtitles**. It has the rows **Size**, **Color** and **Background**. Move right from **Subtitles** to **Appearance**. Press **OK** or **Right** to change the value of a row. The change is at once. A sample line shows above the menu. Press **Left** or **Back** to go back to **Subtitles**. When you move to **Appearance**, **More** closes.
<!-- Spotlight -->
- **Info pop-up.** In the player, move to **Info**. A pop-up shows the logo, the year, the age rating, the length, the story, the ratings and the video and sound badges. The badges are only in this pop-up. The video continues to play. Move to another button to close the pop-up. **OK** on **Info** does nothing.
- **All Genres row.** **Movies** and **Shows** start with an **All Genres** row after **Continue Watching**. It shows titles from all genres. On **Shows**, it does not show news, soap or talk shows. Select the **Showing** card at the start of the row to change the list, for example **Top Rated**. To move or hide the row, select **Settings → Customize Home**.

### Easier to use
- **End time at the top left.** The player shows the end time at the top left, for example **Ends at 2:40 PM**. The clock and the name at the top left are gone. Trailers and live TV show no end time.
- **Audio and subtitle names.** **Audio** and **Subtitles** show only the language, for example **Korean**. Names of add-ons and providers no longer show. Names that uploaders put on tracks no longer show. Under **More**, a commentary, an audio description, a forced track or an SDH track also shows that word. The word comes from the flags of the track or from a word in its name. Only the word shows. Under **More**, audio tracks also show codec and channels, for example **Dolby TrueHD Atmos 7.1**. A track with no language shows **Track 2**. The number is the place of the track in its column. **Off** does not count.
- **Column width.** Each column is as wide as its longest row. The space between the columns is the same. A long name, for example **Portuguese (Brazil)**, no longer pushes **Appearance** off the screen.
- **Menu over HDR.** When the video is HDR, the **Audio & Subtitles** menu dims the picture more. The text in the menu is easier to read.
- **No picture size.** The player always shows the whole picture. **Zoom** and **Stretch** are gone.
- **No Sleep Timer.** The player no longer has a Sleep Timer.
- **No Clear dialogue in the player.** To turn **Clear dialogue** on or off, select **Settings → Picture & Sound → Clear dialogue**.
- **No subtitle timing.** The player no longer has controls to move subtitles earlier or later. If subtitles are out of time, select **More** and select a different subtitle.
- **Simpler badges.** The badges in the player no longer show the audio language, the subtitle language or the debrid service. **REMUX** now shows as **BLU-RAY**. The channels, for example **5.1**, always show when ChadFlix knows them.

## Unreleased

## 1.4

### New

- **Movies in your language.** Titles, stories, posters and episode names follow Alder's language, not only English. Select **Settings → Language → App language**.
<!-- Spotlight: whats-new/p2p.webp -->
- **Torrentio without Real-Debrid.** Choose a **P2P** source from Torrentio and play it right in Alder, without a debrid account.
<!-- Spotlight: whats-new/discover.webp -->
- **Meet the new Discover tab.** Browse movies, shows and anime in **Discover**. Filter by genre, decade or streaming service in your country.
<!-- Spotlight: whats-new/anime.webp -->
- **Anime has its own tab.** Open **Anime** when your add-ons offer an anime catalog. Pick a series to browse its seasons and episodes.
<!-- Spotlight: whats-new/speed.webp -->
- **Watch at your speed.** Choose 0.75× to 2× in **Playback speed**. Alder remembers your choice for the next episode.
<!-- Spotlight -->
<!-- Player: VLC whats-new/external-icon.webp -->
- **Use an external player.** Choose a video app installed on this TV. Alder passes it the stream, playback position and selected subtitle. **Settings → Streams → Video player**.
<!-- Detail: whats-new/sources.webp -->
- **Find more sources.** Alder checks more add-ons for each title. Slower debrid add-ons have up to 45 seconds to answer.
- **Fill more of your TV screen.** Choose **Zoom** or **Stretch** to make black bars smaller. **Audio & subtitles → Sync & Style → Picture**.
- **Read comfortably from the sofa.** Make text, posters and buttons larger across Alder. **Settings → Picture & Sound → Text and poster size → Large**.
- **Hear lossless surround sound.** Alder can send Dolby TrueHD and DTS-HD to compatible audio equipment. When direct output fails, Alder decodes the same track on the TV.
- **See Letterboxd scores in Alder.** Show a film’s Letterboxd rating beside IMDb or on its own. Add a free **MDBList key** to use Letterboxd. **Settings → Profile → Ratings**.
<!-- Detail: whats-new/episodes.webp -->
- **New episodes at a glance.** A **+1** on a series poster means one episode came out since you last watched. Count aired episodes left in the season or turn badges off. **Settings → Profile → New episode badges**.
- **Use Alder in Arabic.** Read menus, buttons and settings in Arabic. **Settings → Language → App language → العربية**.
- **Send a crash report from your TV.** If Alder crashes, you can send a report the next time you open it. The report leaves out your account details.

### Easier to use

- **Home opens in half a second.** **Continue** shows right away, even after a night off, and background pictures come in faster.
- **The sharpest picture, first.** Press Play and Alder picks the sharpest copy your TV can show, not the first one an add-on lists. Your language and quality choices still come first. The **Add-on's order** choice is now called **Any release**.
- **See the updated My Alder.** The README shows how title details appear above the posters.
- **More space in My Alder.** The title and its details have more room below the filters.
- **Brighter art in My Alder and Discover.** The title you select lights up behind its name, and the posters stay easy to see.
- **See more in Discover.** Above the posters, see a title's year or run, genre, seasons and ratings, like in **My Alder**.
- **See more in My Alder.** See a title's year, genre, seasons and ratings. If you add it on this device, see the date too.
- **See Alder in action.** New pictures in the README show Home, Search, My Alder, and the player on a TV.
- **A cleaner look from Home to playback.** Browse with consistent poster cards and smoother transitions. See more of the picture behind the player controls.
- **Find your saved titles in My Alder.** **My Alder** puts your list in a poster grid you can filter by **Genre** or **Year**. Alder asks before removing a title.
- **Put your favorite tabs first.** Show, hide or reorder tabs, including tabs from your add-ons. **Settings → Profile → Tabs**.
- **Change streams and keep your place.** **Change stream** leaves the picture visible while you choose. The new stream picks up at the same point.
- **Compare streams before you press Play.** Check file size, audio format, seeders and debrid status. You can also show the original listing. **Settings → Streams → Source list → As the add-on writes it**.
- **Get to the right title faster.** **Search** puts closer matches first. Type “Matrix” and **The Matrix** appears ahead of the short film called “Matrix”.
- **Find subtitles that match your video.** Alder puts matching subtitles first. Choose **Gray** when you want softer text in a dark room.
- **Keep your place in Movies and Shows.** When you return to a row, the poster you left is still in focus.
- **Find what’s new in Alder.** The release notes show sharper TV pictures, full titles and settings paths.
- **Know what to try when a stream fails.** Alder names the add-on or service that failed and suggests a next step.

### Fixes

- **Full row names.** **More like** rows show the whole movie name, and long row names end with a whole word instead of stopping mid-word.
- **Keep your audio language.** **My language first** tries your language, then English. Select **Settings → Picture & Sound → Fallback audio language** to try another language before English or turn fallback off.
- **Keep your watch progress offline.** Alder saves where you stopped on the TV and sends it to Stremio again when it can connect.
- **Resume from the same spot.** **Continue Watching**, the title page and the player now use the saved position. This also works if you press **Play** before the title page finishes loading.
- **See Skip intro on more episodes.** The button appears on more anime and MKV videos. It can also reappear when you go back into the intro.
- **Keep browsing when an add-on fails.** One failed add-on can no longer clear **Home**. Adding an add-on also keeps your existing list intact.
- **Fewer pauses on large 4K files.** On TVs with enough memory, Alder can keep up to 200 MB of video ready to play.
- **See why an add-on found no sources.** The source list shows which add-ons came back empty and what happened.
- **Stay on the same stream through short dropouts.** A brief network interruption gives the current stream a chance to recover.
- **Load more subtitle files correctly.** Alder handles subtitles from SubDL and OpenSubtitles, subtitles in ZIP files, and Arabic, Polish and Russian text more reliably.
- **Stay paused when you come back.** A video you paused stays paused when you leave Alder and return.
- **See the right Watch Next items on Google TV.** The row follows the active profile. Alder also keeps its channel logo and shows a clearer launcher icon.
- **Open Alder on older TVs again.** Fixed a startup crash on Android 6 and 7 and reduced memory use on older devices.
- **Get the right 4K label and source.** Wide 4K films show **4K**, and **Best picture** can choose a 4K source even on TVs that report a lower resolution.
- **Keep locked profiles behind their PIN.** Alder asks for the PIN when you enter a locked profile from the profile picker.
- **Keep sign-ins tied to this TV.** Android backups exclude Alder profiles, sign-ins and PINs. Sign in again when you move to another TV.
- **Reopen Alder after switching back to an older build.** Fixed a startup crash when Alder reads a profile saved by a newer build.
- **Keep Search results steady as add-ons reply.** Visible posters load first. If a poster still fails after a retry, its card shows the title name.
- **Keep remote focus when the screen updates.** Focus stays on the title page while its details refresh. Removing the last **Continue Watching** poster moves focus to the next row or the top bar.
- **Keep Discover results in step with your filters.** Fast filter changes show the latest results. Larger text leaves room for the filter bar, and Right works across **Discover** and **My Alder** filters in Arabic.

## 1.3.1

### Fixes
- **Alder.apk download.** The release file uses the Alder.apk name.

## 1.3

### New
<!-- Spotlight -->
- **Alder in your language.** Alder shows menus, buttons and settings in Spanish, Portuguese, French, German, Hindi or Italian. Alder uses the TV language. To change it, select **Settings → Language → App language**.
<!-- Spotlight -->
- **Trailers on the title page.** Select **▷ Trailer** on a movie or show to play its trailer. Alder does not add trailers to your watch history.
- **Subtitle colors.** Choose White, Yellow, Cyan, Cream, or Warm white in **Settings → Subtitles** or while you watch in **Audio & subtitles**.
<!-- Spotlight -->
- **Live TV and sport.** If your add-ons have TV channels, sport or events, select **Live** at the top. Select a channel to play it. To choose a source, hold OK.
<!-- Spotlight -->
- **Directors and cast.** See directors and lead actors on the title screen. Select a person to see their other works. A label shows the title and year of the focused poster. Select **ⓘ** to see the full story and all cast members with portraits.
<!-- Spotlight -->
- **Chapter markers and chapter snap.** See chapter marks and intro marks on the progress bar. Press Up on the bar to jump between chapters.
<!-- Spotlight -->
- **Clear dialogue.** Turn on **Settings → Clear dialogue** to boost voices above music and loud effects on TV speakers. You can also turn it on while you watch.
- **Foreign audio subtitles.** Alder can start subtitles automatically only when the audio is in a different language. Select **Settings → Subtitles → Foreign audio only**.
- **Still watching?** If you do not press a remote button for 3 episodes, Alder stops before the next episode. Select **Play** or **Exit to Home**.
- **Sleep timer.** In the player, select **Audio & subtitles**, then select **Sleep**. Choose **After this episode** or **In 60 min**.
- **Mark episodes as watched.** Hold OK on an episode in the episode guide. Select **Mark as watched**, **Mark as unwatched**, or **Mark previous episodes watched**. Hold OK on a season button to mark all episodes of that season.
- **New episode tags.** A tungsten tag shows on Continue Watching when a new episode is available.
- **Protect unreleased episodes.** Alder shows the air date for future episodes and disables playback until they air.
- **Default tab.** Choose which tab opens when you start Alder. Select **Settings → Profile → Default tab**.
- **Show or hide tabs.** Turn tabs on or off in **Settings → Profile**.
- **Featured rows on Home.** Show top recommendations and popular titles below Continue Watching. Turn on **Settings → Profile → Featured on Home**.
- **Hide rows.** Hold OK on a title in a row, then select **Hide row**. To put it back, select **Settings → Profile → Hidden rows**.
- **30 GB file limit.** Select **30 GB** in **Settings → Largest file**.
- **Blur episode thumbnails.** Turn on **Settings → Profile → Blur episode thumbnails**. Alder blurs pictures of unwatched episodes to prevent spoilers.

### Easier to use
<!-- Spotlight -->
- **House Lights logos.** Alder uses the tungsten mark on the TV banner, transparent app icon, and small transparent logo. The profile picker and Settings headers use vector art.
- **Content ratings on the title page.** A badge shows the content rating, for example PG-13, R, or TV-MA, before the year.
- **Compact Up next prompt.** The Up next card uses a smaller width, smaller text, and a single Next episode button.
- **QWERTY Search keys.** The Search keyboard uses QWERTY letter rows. The microphone icon stays dim until voice search is available.
- **Audio and subtitle tabs.** Select **Subtitles**, **Audio**, or **Sync & Style**. Active, preferred, and recent tracks appear first. Adjust subtitle sync in the panel.
- **Clearer title page.** Cast names stay clear in dark glass capsules.
- **Clearer menus.** Add-on lists, Search, and choice menus use glass that is easy to read.
- **Clearer player controls.** The control bar shows more video. Player tags and panels use dark glass.
- **What's new showcase.** The update screen shows important changes first. Select **See all changes** to read the full list.
<!-- Spotlight -->
- **Direct play.** When you select Play, Alder starts the video directly. A progress indicator shows while Alder finds the best source. To see all sources, hold OK.
<!-- Spotlight -->
- **Cinematic video start.** When you select Play, Alder shows the title art and bottom controls immediately. The progress bar pulses with warm light while Alder connects to the source, and the video fades in smoothly.
- **Format and debrid badges in the player.** Open the player controls to see badges for resolution, Dolby Vision, HDR, Dolby Atmos, DTS, audio language, subtitles, and your debrid service below the title.
- **Faster access to Audio & subtitles.** Press Left once from Play / Pause in the player to open **Audio & subtitles**.
- **Live subtitle sync.** Adjust subtitle delay with Left and Right while the video plays. This works for embedded subtitles and add-on subtitles.
- **Close player controls.** Press Back to close the controls while the video plays. Press Back again to leave the player.
- **Exact subtitle remembered.** When you play a video again, Alder uses the exact subtitle track that you selected before.
- **Update notification dot.** A dot on your profile avatar shows when a new version of Alder is ready.
- **Install an update from the profile menu.** Select your profile avatar, then select **Install update**. Alder installs version 1.3 over version 1.2.
- **Faster update checks.** Alder checks for new releases every 4 hours instead of once a day.

### Fixes
- **Subtitle language list in Audio & subtitles.** All add-on subtitle languages appear under Subtitles. Quick sync sits below the list so remote focus moves smoothly.
- **Up next episode card.** When credit times are available, Alder shows Up next only after the credits start. The fallback starts in the final 15 to 25 seconds.
- **Skip intro button.** Alder reads MKV intro chapters first. Alder filters IntroDB segments with low confidence and stops false prompts at the start of a video.
- **Remote focus and scroll in Audio & subtitles.** When you select a track, remote focus stays on it and the list keeps its position. The list moves smoothly and stays in its panel.
- **Smoked glass overlay in Audio & subtitles.** The Audio & subtitles sheet uses the Alder smoked glass recipe for high contrast over video.
- **Cleaner title page.** The light around **Play** and **Resume** sits in the center. Cast names stay inside the screen margins. Action buttons align in the center.
- **Install on Fire TV.** Alder installs on Fire TV and Fire OS devices without a package parse error.
- **Support older TV devices.** Alder supports Android 6.0 and Fire OS 6 devices, such as Fire TV Stick 4K.
- **Cleaner player controls.** The audio, subtitle, format, and debrid badges sit in one row below the title, and the top progress bar no longer appears while you seek.
- **Back button on Android 16.** The Back button closes panels and player controls correctly on Android 16.

## 1.2

### New
- **Sources in your language.** Select a language above the source list. Alder shows only the sources in that language.
- **All audio and subtitle languages.** Select them in **Settings**. Each profile keeps its own languages.
- **Alder keeps your player choices.** The next episode starts with the audio and the subtitles that you selected.
- **Remove titles from Continue Watching.** Hold OK on a title. To put it back, select **Settings → Profile → Hidden from Continue Watching**.
- **See the debrid service.** The source list and the player show the service, for example **RD** for Real-Debrid.

### Easier to use
- **Help when there is no source.** Alder tells you why. Scan the QR code to set up the add-on on your phone. Then select **Try again**.
- **See your subtitles quickly.** The player shows the audio and the subtitles that play.
- **What's new after each update.** To read it again, select **Settings → About → What's new**.

### Fixes
- **Fixed sources that did not show.** Add-ons with an http:// address work. Some add-ons do not block Alder now. Dolby Vision sources show on all TVs.
- **Fixed skip forward.** Some movies, for example .ts files, do not start again from the beginning.
- **Fixed empty pages.** When an add-on gives no details, Alder uses the next add-on. All AIOMetadata catalogs show.
- **Fixed sign-in problems.** A profile stays after Stremio signs it out. Alder tells you when 2 profiles use the same account.
- **Fixed updates on Fire TV.** Alder does not stop during an update.
- **Fixed language problems.** Subtitles and audio tracks match in all add-ons.

## 1.1

- Each profile keeps its own streaming add-ons. Add-ons that you add or remove in Stremio show in Alder on the next page.
- Continue Watching shows correctly when Home opens for the first time.
- The **Audio & subtitles** panel in the player opens correctly.
- In **Search**, the selected poster is no longer cut off at the top.

## 1.0

- **Press Play and it plays.** Alder picks a good source and starts it. Hold OK on **Play** to see every source.
- **Automatic fallback.** When a stream fails, Alder tries the next one from the same second.
- **Only what your TV can play.** Alder checks your TV for 4K, HDR, Dolby Vision, HEVC, AV1 and DTS. It hides files that the TV cannot play.
- **Profiles.** Up to 5 profiles, each with its own Stremio account. A PIN is optional.
- **Stream preferences in plain words:** **Best picture**, **Balanced** or **Fast start**.
- **Series.** The next episode keeps the same release and quality. **Skip intro** is built in.
- **Continue Watching on the Google TV home screen.**
- **Frame-rate matching.** The TV changes to the film's frame rate.
- **Sync with Stremio.** Watch progress matches your phone and computer.
- **Updates.** Alder finds new versions on GitHub and installs them.
