Real photos for testing what "Blur faces & private info" finds besides faces.

Seven photos from Flickr via Open Images V7, all under Creative Commons Attribution 2.0
(https://creativecommons.org/licenses/by/2.0/). Author, title and source of each photo are in
CREDITS.tsv. Changes: each photo was scaled down (at most 1,280 px). Only the photographers' own
pictures were used: no edits, manipulations, screen grabs or reposts.

Three photos of cars and buses with number plates (one with a woman by the car, one a street with
people walking), two adverts with phone numbers, e-mail and street addresses (businesses', printed
in the adverts), a QR code on a belt buckle and a barcode wristband on an arm.

labels.tsv: the plates people marked (Open Images boxes, readable ones), the vehicles the app's
object detector finds, phone numbers and e-mail addresses with TextOCR's outlines (CC BY 4.0), and
the codes, outlined by OpenCV and checked by eye.

Made by buildtools/privacy/make_fixture.py.
