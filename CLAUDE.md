# Local Media Tools — notes for working on this repo

- Build and test: `python3 buildtools/build_apk.py --test --robo-test` (no Gradle; toolchain from
  `buildtools/fetch_toolchain.py`). Version and release: `VERSION_CODE`/`VERSION_NAME` in
  `buildtools/build_apk.py`, APK in `release/`, README "What's new" section.
- **Test with photos of real people.** Anything involving faces or people (detection, grouping,
  blurring, gallery screens, screenshots) is tested on real photos, never only on synthetic faces or
  embeddings. Use royalty-free photos with clear licences (e.g. CC BY from Open Images/Flickr) and
  keep their credits (author, title, source, licence, changes) next to them; no edits, screen grabs or
  promotional reposts. Cover headwear, glasses and sunglasses, make-up, expressions, angles (turned
  and profile) and small, far-away faces, plus other people in the background.
  The current set is `app/src/test/resources/people` (made by `buildtools/gallery/people/make_fixture.py`);
  the larger labelled set for tuning is described in `buildtools/gallery/README.md`.
- Recognition settings are measured, not guessed: change `ClusterParams` or the analyzers only with
  numbers from the real-photo evaluation (`buildtools/gallery/people/final_eval.py`), checked on
  people the settings were not tuned on.
