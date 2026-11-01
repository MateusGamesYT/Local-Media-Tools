# Blur faces & private info: models and measurements

What the tool finds besides faces, and how well, measured on real photos. Nothing here ships in the
app except what `build_models.py` and `make_names.py` write into `app/src/main/assets`.

## Scripts

- `build_models.py` fetches the plate, text-detection and text-recognition models (checked by SHA-256),
  fixes the recogniser's input to 48 × 320, stores weights as 16-bit numbers and checks the outputs.
- `make_names.py` writes the first-name and surname lists (US SSA, CC0; IBGE Censo 2022 compiled by
  Gabriel Lobo, CC BY 4.0; INSEE compiled by Ronan Le Meillat, MIT).
- `fetch_oi.py`, `fetch_codes.py`, `fetch_textocr.py` download the measurement photos (Open Images V7,
  CC BY 2.0; TextOCR annotations, CC BY 4.0) with their credits into a work directory
  (`buildtools/dl/privacy`, not committed).
- `eval_faces.py`, `eval_plates.py` (Python, mirroring the app), and `eval_private.py`,
  `eval_codes.py`, which run the app's own Kotlin code through `harness/` (desktop OpenCV 4.9; phones
  run 4.12).
- `make_fixture.py` writes the test photos in `app/src/test/resources/privacy` (with credits).

## Results (November 2026)

- **Faces** (`eval_faces.py`, Open Images faces): the photo at up to 1,600 px plus copies at 800, 400
  and 256 px, score 0.5, boxes of one face merged and widened to cover each other. Faces under the
  blur (80 % of the marked face covered by the app's ellipse), on 700 test photos (settings chosen on
  the validation photos): 86.8 % (one look at 0.62, as before: 77.0 %); validation 91.0 % (82.3 %);
  video frames at 960 px with 640, 400, 256: 86.4 % (76.9 %). Boxes on no marked face: 1.33 per photo
  (0.97); a contact sheet of them showed mostly unmarked real faces, about one in five something
  else. Tiles at twice the resolution added 0.4 points for 1.5 more boxes per photo: not used.
- **Plates** (`eval_plates.py`, Open Images plates; 248 of the first 300 validation photos' 398
  plates marked readable by eye in `plates_readable.json`): plate model alone (1,024 px, 0.5): 77.4 %
  of readable plates covered; with the second look inside vehicles (EfficientDet-Lite2, crops to
  960 px, plate model at 0.4 plus any text there): 89.9 %; with plate-shaped text on vehicles too:
  94.4 %, and with the check that plate-model boxes are shaped like plates (as shipped): 94.0 %, 3.5
  other boxes per photo. Test photos (700, all 943 plates, readable or not): 65.9 %, 3.3 other boxes.
  Keeping whole-photo text that is plate-shaped *or* on a vehicle (not both) reached 95.2 %, with 5.4
  other boxes per photo.
- **Text** (`eval_text.py`, 400 TextOCR photos, 15,784 legible words): 79.2 % of words under the
  blur (PP-OCRv5's detector, fixed to 1,280 × 1,280 so OpenCV runs it, found about two points more
  on 200 photos at twice the time: not used); words read exactly 48.9 % (four or more letters 51.9 %).
- **Private details** (`eval_private.py`): on the 175 TextOCR photos with a phone number or e-mail
  address, 17 of 18 e-mail addresses and 131 of 189 numbers of eight or more digits (not dates,
  prices or sizes) hidden; with every line hidden 18 of 18 and 155 of 189. On the 400 random photos,
  0.33 lines per photo taken as private; the sheet (`--sheet`) was checked by eye and the false
  alarms it showed (dates, spans of years, times run together by the reader) fixed in `PrivateText`.
- **Codes** (`eval_codes.py`, 34 Open Images photos marked as showing a QR code or barcode): QR codes
  in 7 of the 8 photos with one (OpenCV 4.12; 6 with the harness's 4.9), barcodes in 16 of 28, by eye.

These are measurements on photos, not on a phone.
