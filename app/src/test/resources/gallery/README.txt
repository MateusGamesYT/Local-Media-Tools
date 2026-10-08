scene_golden.bin: 40 feature vectors (EfficientNetV2-B3 21k backbone, 1536 floats) of Open Images V7
validation photos (annotations CC BY 4.0), with the fused category scores the calibration scripts
computed from the shipped scene head and category table. No images are included.
Layout (little-endian): count, dim, categories, then per record dim floats + categories floats.
