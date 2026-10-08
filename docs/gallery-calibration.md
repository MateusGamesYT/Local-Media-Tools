# Gallery: how well each category is recognised

Every searchable category ("Dogs", "Beach", "Pizza"…) is recognised by up to three on-device
signals: the object detector (EfficientDet-Lite2), the scene classifier (EfficientNetV2-B3 trained on
ImageNet-21k, its 21,843 classes grouped by WordNet), and a small trained "probe" for scenes the
classifier has no class for. Each signal has its own threshold; a photo gets the category when any
signal passes its threshold.

**How the thresholds were set.** On 13,455 Open Images V7 validation photos with human-verified
labels, run through exactly what the app runs (int8-weight classifier, 4-bit output layer,
letterboxed detector), the thresholds were chosen for at least 90 % precision on the verified
labels; where that left too few photos found, 80 %. Precision and recall below are **2-fold
cross-validated**: thresholds chosen on one half of the photos, measured on the other half, and
averaged. Probes were trained on separate Open Images *train* photos.

**How to read it.** Precision: of the photos the gallery tags with the category, the share that
really show it. Recall: of the photos that show it, the share it finds. Open Images' verified
negatives are mostly hard look-alikes (photos where an earlier model guessed the label and a person
said no), so precision on a normal photo library is usually higher than measured here.

71 of 86 categories reach at least 85 % precision (median 90 %); 15 are between 78 % and 84 %.
Categories that couldn't reach this were left out rather than shipped: trees, plants, buildings,
churches, restaurants, statues, shoes, hats, glasses, watches, books, computers, TVs, toys, cups,
trucks, bears, vegetables, bread, Christmas, birthdays, fireworks and others.

\* Beach and lake: their verified negatives are almost all other shores and water ("a sea shore,
not a beach"), so for these the number is the share of **all** photos the gallery tags (verified or
not) that are verified beach, sea, coast or shore photos (lake, river, pond or water for lake).

| Category | Group | Precision | Recall | Signals used | Verified photos (yes / no) |
|---|---|---|---|---|---|
| People | People | 97 % | 88 % | detector | 3895 / 2196 |
| Dogs | Animals | 98 % | 93 % | classifier | 391 / 83 |
| Cats | Animals | 98 % | 86 % | detector, classifier | 188 / 62 |
| Birds | Animals | 93 % | 89 % | classifier | 281 / 99 |
| Horses | Animals | 80 % | 84 % | detector, classifier | 98 / 76 |
| Fish | Animals | 96 % | 72 % | classifier | 115 / 98 |
| Insects | Animals | 86 % | 45 % | classifier | 84 / 84 |
| Butterflies | Animals | 98 % | 82 % | classifier | 60 / 36 |
| Cows | Animals | 90 % | 66 % | detector, classifier | 100 / 77 |
| Sheep | Animals | 85 % | 48 % | detector, classifier | 61 / 52 |
| Goats | Animals | 92 % | 61 % | classifier | 60 / 35 |
| Pigs | Animals | 96 % | 55 % | classifier | 51 / 14 |
| Elephants | Animals | 87 % | 90 % | classifier | 33 / 24 |
| Zebras | Animals | 88 % | 88 % | classifier | 26 / 13 |
| Giraffes | Animals | 87 % | 93 % | detector | 15 / 14 |
| Monkeys | Animals | 95 % | 87 % | classifier | 79 / 61 |
| Lions | Animals | 98 % | 83 % | classifier | 57 / 9 |
| Tigers | Animals | 89 % | 76 % | classifier | 19 / 18 |
| Rabbits | Animals | 98 % | 89 % | classifier | 61 / 18 |
| Reptiles | Animals | 88 % | 33 % | classifier | 110 / 65 |
| Animals | Animals | 90 % | 88 % | detector, classifier | 1313 / 364 |
| Cars | Vehicles | 96 % | 80 % | detector, classifier | 671 / 537 |
| Buses | Vehicles | 90 % | 78 % | detector, classifier | 66 / 49 |
| Motorcycles | Vehicles | 86 % | 76 % | detector | 82 / 64 |
| Bicycles | Vehicles | 88 % | 80 % | detector, classifier | 114 / 89 |
| Trains | Vehicles | 90 % | 72 % | detector, classifier | 64 / 58 |
| Airplanes | Vehicles | 93 % | 88 % | classifier | 159 / 116 |
| Helicopters | Vehicles | 98 % | 83 % | classifier | 76 / 30 |
| Boats | Vehicles | 92 % | 74 % | detector | 269 / 99 |
| Food | Food | 88 % | 76 % | detector, classifier, probe | 1177 / 713 |
| Pizza | Food | 94 % | 80 % | classifier | 67 / 29 |
| Cakes | Food | 83 % | 79 % | detector | 144 / 81 |
| Burgers | Food | 96 % | 77 % | classifier | 70 / 14 |
| Sushi | Food | 95 % | 68 % | classifier | 47 / 20 |
| Ice cream | Food | 82 % | 40 % | classifier | 73 / 68 |
| Fruit | Food | 80 % | 41 % | detector, classifier | 181 / 203 |
| Salads | Food | 83 % | 43 % | classifier | 92 / 60 |
| Desserts | Food | 80 % | 52 % | detector, classifier | 289 / 329 |
| Coffee | Food | 91 % | 54 % | classifier | 136 / 79 |
| Drinks | Food | 91 % | 51 % | classifier | 257 / 220 |
| Wine | Food | 90 % | 58 % | classifier | 81 / 56 |
| Beer | Food | 94 % | 70 % | classifier | 85 / 67 |
| Cocktails | Food | 90 % | 49 % | classifier | 95 / 73 |
| Beach | Nature | 85 %* | 91 % | probe | 76 / 102 |
| Sea | Nature | 93 % | 66 % | probe | 398 / 129 |
| Mountains | Nature | 88 % | 86 % | classifier, probe | 162 / 32 |
| Lakes | Nature | 93 %* | 36 % | probe | 73 / 32 |
| Rivers | Nature | 83 % | 52 % | classifier, probe | 149 / 52 |
| Waterfalls | Nature | 84 % | 89 % | probe | 47 / 15 |
| Forest | Nature | 93 % | 84 % | probe | 104 / 14 |
| Flowers | Nature | 93 % | 62 % | classifier, probe | 560 / 729 |
| Snow | Nature | 90 % | 68 % | classifier, probe | 183 / 67 |
| Sky | Nature | 93 % | 93 % | classifier, probe | 228 / 35 |
| Sunsets | Nature | 92 % | 65 % | probe | 68 / 15 |
| Night | Nature | 91 % | 97 % | probe | 99 / 13 |
| Desert | Nature | 95 % | 91 % | probe | 56 / 9 |
| Grass & fields | Nature | 89 % | 34 % | classifier | 446 / 123 |
| Rocks & cliffs | Nature | 92 % | 33 % | classifier | 219 / 76 |
| City | Places | 87 % | 51 % | classifier, probe | 111 / 60 |
| Castles | Places | 89 % | 29 % | classifier | 32 / 9 |
| Bridges | Places | 86 % | 71 % | classifier | 87 / 38 |
| Streets | Places | 95 % | 50 % | classifier | 131 / 23 |
| Swimming pools | Places | 78 % | 63 % | probe | 117 / 101 |
| Kitchens | Places | 97 % | 78 % | classifier | 41 / 14 |
| Bedrooms | Places | 100 % | 97 % | classifier | 38 / 1 |
| Bathrooms | Places | 90 % | 71 % | detector, classifier | 59 / 19 |
| Towers | Places | 83 % | 37 % | classifier | 118 / 123 |
| Fountains | Places | 88 % | 39 % | classifier | 21 / 39 |
| Weddings | Events | 87 % | 74 % | probe | 28 / 14 |
| Concerts | Events | 80 % | 50 % | probe | 24 / 36 |
| Football | Sports | 96 % | 26 % | classifier | 88 / 111 |
| Tennis | Sports | 91 % | 88 % | detector, classifier | 55 / 25 |
| Skiing | Sports | 78 % | 67 % | classifier | 37 / 18 |
| Surfing | Sports | 85 % | 69 % | detector, classifier | 42 / 32 |
| Documents | Things | 91 % | 76 % | classifier, probe | 221 / 37 |
| Phones | Things | 93 % | 44 % | detector, classifier | 70 / 49 |
| Bags | Things | 90 % | 29 % | classifier | 83 / 57 |
| Furniture | Things | 89 % | 57 % | detector, classifier | 463 / 254 |
| Sofas | Things | 78 % | 77 % | detector, classifier | 52 / 71 |
| Beds | Things | 89 % | 69 % | detector, classifier | 110 / 79 |
| Clocks | Things | 93 % | 31 % | classifier | 24 / 44 |
| Bottles | Things | 85 % | 77 % | detector | 132 / 101 |
| Umbrellas | Things | 78 % | 47 % | classifier | 27 / 37 |
| Guitars | Things | 91 % | 60 % | classifier | 70 / 94 |
| Pianos | Things | 91 % | 43 % | classifier | 66 / 32 |
| Music | Things | 88 % | 34 % | classifier | 109 / 135 |
