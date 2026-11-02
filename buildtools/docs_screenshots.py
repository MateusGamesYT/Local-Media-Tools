"""Copies the screenshots the tests render (LMT_SHOTS=<dir> python3 buildtools/build_apk.py --robo-test)
into docs/screenshots under the names the README uses.

    python3 buildtools/docs_screenshots.py <dir>
"""
import os, shutil, sys

NAMES = {
    '00_home': 'home', 'dark_00_home': 'home-dark', '02_settings': 'settings', 'dark_02_settings': 'settings-dark',
    '40_gallery_photos': 'gallery-photos', 'gallery-search-describe': 'gallery-search-describe', '43_gallery_search': 'gallery-search',
    '44_gallery_viewer_faces': 'gallery-viewer', '41_gallery_people': 'gallery-people', '42_gallery_things': 'gallery-things',
    '45_gallery_person': 'gallery-person', '46_gallery_all_faces': 'gallery-faces',
    'video-editor': 'video-editor', 'video-editor-reframe': 'video-editor-reframe',
    '36_highlight': 'highlight', '33_duplicates': 'duplicates', '33b_duplicates_review': 'duplicates-review', '47_name_people': 'name-people',
    'reader_0_home_recent': 'reader', 'reader_1_docx': 'reader-word', 'reader_2_xlsx': 'reader-sheet',
    'reader_3_SampleShow.pptx': 'reader-slides', 'reader_4_pdf_search': 'reader-pdf',
    'metadata_1_photo': 'metadata', 'metadata_4_docx': 'metadata-docx', 'metadata_5_doc': 'metadata-doc',
    '33_trim_audio': 'trim-audio', '34_remove_silence': 'remove-silence', '35_volume': 'volume',
    '50_print': 'print', '51_print_printers': 'print-printers',
    '40_tool_stack': 'tool-stack', '41_stack_step': 'stack-step', '42_stack_picker': 'stack-picker',
    '33_denoiser': 'denoiser', '32_face_blur': 'face-blur', '30_background_remover': 'background-remover',
    '31_auto_enhance': 'auto-enhance', '35_video_speed': 'speed',
    'lang_pt-BR_home': 'home-pt-BR', 'lang_zh-Hans_home': 'home-zh-Hans', 'lang_ja_home': 'home-ja', 'lang_ru_home': 'home-ru',
    '13_editor_erase': 'editor-erase', '12_editor_filters': 'editor-looks', '10_editor_crop': 'editor-crop',
}

src = sys.argv[1]
dst = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'docs', 'screenshots')
missing = []
for a, b in NAMES.items():
    f = os.path.join(src, a + '.png')
    if os.path.exists(f): shutil.copyfile(f, os.path.join(dst, b + '.png'))
    else: missing.append(a)
print(f'{len(NAMES) - len(missing)} copied' + (f'; missing: {", ".join(missing)}' if missing else ''))
