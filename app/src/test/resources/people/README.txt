Real photos of real people for testing face detection and people grouping.

63 photos from Flickr via Open Images V7, all under Creative Commons Attribution 2.0
(https://creativecommons.org/licenses/by/2.0/). Author, title and source of each photo are in
CREDITS.tsv. Changes: each photo was cropped around a person and scaled down (at most 800 px).
Only the photographers' own pictures were used: no edits, manipulations, screen grabs or reposts.

They show nine public figures (musicians, actors, athletes, speakers) photographed by different
people over several years, with caps, visors and hats, glasses and sunglasses, stage make-up,
strong expressions, turned and profile faces and small faces far from the camera, plus 25 other
people who happen to be in the pictures. Who each labelled face is was checked by eye.

faces.tsv: every face the gallery's pipeline (YuNet + SFace, flip-averaged) finds in each photo,
as fractions of the photo, with detector score, eye distance (px), yaw, the person (empty for
the other people in the picture), tags (H headwear, G glasses, M heavy make-up, E strong
expression), roles and the 128-number embedding. Roles: "jvm" marks the 6 faces per person chosen
to cover the conditions above; A1..A10, A-small, B1..B12 and C1..C3 are the faces the Robolectric
fake gallery shows (its UI tests need a gallery that groups one known way).

Made by buildtools/gallery/people/make_fixture.py.
