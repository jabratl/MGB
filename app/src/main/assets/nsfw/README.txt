Put the picture-check model here (not included, you supply it):
  model.tflite   input 1 x H x W x 3 (RGB, float32 0..1 or uint8), e.g. 224 x 224
  labels.txt     optional, one label per line in output order (labels porn / hentai / sexy / nsfw / explicit / unsafe count as unsafe)
Without labels.txt: 1 output = score, 2 outputs = index 1, 5 outputs (drawings, hentai, neutral, porn, sexy) = indexes 1 + 3 + 4.
Check the model's license before shipping it. No model here = the picture check does nothing.
