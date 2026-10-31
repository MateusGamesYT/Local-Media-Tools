#!/bin/bash
# Builds app/src/test/resources/meta: small files made for the metadata tests (test patterns from
# ffmpeg with tags written by ExifTool and ffmpeg, PDFs from make_pdf.py and LibreOffice), with their credits.
# Needs ffmpeg, exiftool, LibreOffice (soffice) and Python's "cryptography".
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
D=$R/app/src/test/resources/meta
W=$(mktemp -d)
mkdir -p "$D"
q() { ffmpeg -v error -y "$@"; }
# A photo with the usual personal details: camera, owner, place, time, description, keywords.
q -f lavfi -i testsrc=size=320x240 -frames:v 1 "$D/photo.jpg"
exiftool -q -overwrite_original -Make=TestCam -Model="TestCam One" -SerialNumber=SN-123456 -Artist="Ana Souza" -OwnerName="Ana Souza" \
  -DateTimeOriginal="2023:07:14 18:30:05" -OffsetTimeOriginal=-03:00 -GPSLatitude=22.9519 -GPSLatitudeRef=S -GPSLongitude=43.2105 -GPSLongitudeRef=W \
  -GPSAltitude=710 -ImageDescription="Family trip" -XMP-dc:Creator="Ana Souza" -XMP-dc:Subject=trip -IPTC:Keywords=trip -IPTC:Keywords=family \
  -IPTC:City="Rio de Janeiro" -Software="Test Editor 2.1" "$D/photo.jpg"
q -f lavfi -i testsrc=size=160x120 -frames:v 1 "$D/photo.png"
exiftool -q -overwrite_original -XMP-dc:Creator="Ana Souza" -XMP-dc:Title="Drawing" -PNG:Comment="made for the tests" "$D/photo.png"
# A video with a place (3GPP location box and QuickTime key) and a title.
M="-metadata title=Holiday -metadata artist=Ana_Souza -metadata comment=Beach -metadata date=2023"
q -f lavfi -i testsrc=size=64x48:rate=10 -f lavfi -i sine=sample_rate=16000 -t 1 -c:v libx264 -c:a aac $M \
  -metadata location="-22.9519-043.2105/" -movflags +use_metadata_tags "$D/clip.mp4"
q -i "$D/clip.mp4" -c copy $M -metadata location="-22.9519-043.2105/" "$D/clip.mkv"
# Songs with tags and a cover picture.
q -f lavfi -i testsrc=size=32x32 -frames:v 1 "$W/cover.png"
S="-metadata title=Song -metadata artist=Band -metadata album=Album -metadata date=2020 -metadata track=2/10 -metadata genre=Rock"
q -f lavfi -i sine=sample_rate=22050 -i "$W/cover.png" -t 1 -map 0 -map 1 -c:a libmp3lame -q:a 6 -id3v2_version 3 -write_id3v1 1 $S "$D/song.mp3"
q -f lavfi -i sine=sample_rate=22050 -i "$W/cover.png" -t 1 -map 0 -map 1 -c:a flac -c:v png -disposition:v attached_pic $S "$D/song.flac"
q -f lavfi -i sine=sample_rate=48000 -t 1 -c:a libopus -b:a 16k $S "$D/song.opus"
q -f lavfi -i sine=sample_rate=8000 -t 1 -c:a pcm_u8 $S "$D/song.wav"
# PDFs: protected only against changes (RC4 by LibreOffice, AES-256 by make_pdf.py), and one that needs a password.
cp "$R/app/src/test/resources/docs/boreas.odt" "$W/"
timeout 180 soffice -env:UserInstallation=file://$W/lo --headless --norestore --convert-to \
  'pdf:writer_pdf_Export:{"RestrictPermissions":{"type":"boolean","value":"true"},"PermissionPassword":{"type":"string","value":"owner"},"Changes":{"type":"long","value":"0"}}' \
  --outdir "$W" "$W/boreas.odt" >/dev/null 2>&1
cp "$W/boreas.pdf" "$D/restricted.pdf"
python3 -I "$R/buildtools/meta/make_pdf.py" "$D"
# A Word file with its properties, including the hidden custom ones Office adds when a document is e-mailed.
python3 -I - "$D/props.docx" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED)
z.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
  '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>'
  '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
  '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
  '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>'
  '<Override PartName="/docProps/custom.xml" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/></Types>')
z.writestr("_rels/.rels", '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
  '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
z.writestr("word/document.xml", '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p></w:body></w:document>')
z.writestr("docProps/core.xml", '<?xml version="1.0"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
  'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
  '<dc:title>Budget</dc:title><dc:creator>Ana Souza</dc:creator><cp:lastModifiedBy>Bruno Lima</cp:lastModifiedBy><cp:revision>7</cp:revision>'
  '<dcterms:created xsi:type="dcterms:W3CDTF">2024-03-01T09:00:00Z</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">2024-03-02T10:30:00Z</dcterms:modified>'
  '<cp:keywords>money, plan</cp:keywords></cp:coreProperties>')
z.writestr("docProps/app.xml", '<?xml version="1.0"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" '
  'xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><Template>Normal.dotm</Template><TotalTime>95</TotalTime>'
  '<Pages>1</Pages><Application>Microsoft Office Word</Application><Company>Example Ltda</Company><DocSecurity>0</DocSecurity>'
  '<TitlesOfParts><vt:vector size="1" baseType="lpstr"><vt:lpstr>Budget</vt:lpstr></vt:vector></TitlesOfParts></Properties>')
z.writestr("docProps/custom.xml", '<?xml version="1.0"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" '
  'xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">'
  '<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="2" name="_AuthorEmail"><vt:lpwstr>ana@example.com</vt:lpwstr></property>'
  '<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="3" name="Project code"><vt:lpwstr>X-17</vt:lpwstr></property></Properties>')
z.close()
PY
rm -rf "$W"
ls -la "$D"
