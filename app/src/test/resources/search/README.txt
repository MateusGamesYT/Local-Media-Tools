Real photos for testing the Gallery's search by description (SearchRealPhotosTest).

Sixty photos from Flickr via Open Images V7, all under Creative Commons Attribution 2.0
(https://creativecommons.org/licenses/by/2.0/). Author, title and source of each photo are in
CREDITS.tsv. Changes: each photo was scaled down (at most 320 px). Only the photographers' own
pictures were used: no edits, manipulations, screen grabs or reposts.

Three photos each of sixteen kinds of things people confirmed in Open Images (dogs, cats, cars,
cakes, bicycles, boats, guitars, snow, aeroplanes, trains, balloons, umbrellas, pizza, books, horses,
birds) and twelve photos of other things (two photos without a "car" label that show cars were
left out after looking at them). Some show people.

photos.tsv: each photo's label and its description by the app's own converted search model
(SigLIP 2 B/32, 8-bit TFLite), packed as the app stores it. queries.tsv: thirty searches in the app's
ten languages, described through the app's tokenizer and token table. tokens.tsv: texts in the ten
languages with the original tokenizer's ids. Made by buildtools/search/make_fixture.py and
build_search_model.py.
