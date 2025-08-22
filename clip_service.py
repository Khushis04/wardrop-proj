from fastapi import FastAPI
from pydantic import BaseModel
from typing import List
from PIL import Image
import requests
import torch
from transformers import CLIPProcessor, CLIPModel
from torch.optim import AdamW

from io import BytesIO
import sqlite3
import os

# ---------------- App Setup ----------------
app = FastAPI()
device = "cuda" if torch.cuda.is_available() else "cpu"

# Load base CLIP
model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32").to(device)
processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")

DB_FILE = "ratings.db"

# ---------------- Database Setup ----------------
def init_db():
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute("""
        CREATE TABLE IF NOT EXISTS ratings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            outfit_id TEXT,
            image_url TEXT,
            keyword TEXT,
            rating INTEGER
        )
    """)
    conn.commit()
    conn.close()

init_db()

# ---------------- Request Models ----------------
class ImageKeywordsRequest(BaseModel):
    outfit_id: str
    image_url: str
    keywords: List[str]

class RatingRequest(BaseModel):
    outfit_id: str
    image_url: str
    keyword: str
    rating: int  # 1–5

# ---------------- Analyze Endpoint ----------------
@app.post("/analyze")
def analyze(req: ImageKeywordsRequest):
    # Load image
    response = requests.get(req.image_url)
    image = Image.open(BytesIO(response.content)).convert("RGB")

    # Prepare text
    texts = req.keywords
    inputs = processor(text=texts, images=image, return_tensors="pt", padding=True).to(device)
    outputs = model(**inputs)
    
    # Cosine similarity
    image_embeds = outputs.image_embeds / outputs.image_embeds.norm(p=2, dim=-1, keepdim=True)
    text_embeds = outputs.text_embeds / outputs.text_embeds.norm(p=2, dim=-1, keepdim=True)
    similarity = (image_embeds @ text_embeds.T).squeeze(0)  # shape = [num_keywords]
    scores = similarity.tolist()

    return {
        "outfit_id": req.outfit_id,
        "scores": {kw: float(score) for kw, score in zip(texts, scores)}
    }

# ---------------- Rate Endpoint ----------------
@app.post("/rate")
def rate_outfit(req: RatingRequest):
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute(
        "INSERT INTO ratings (outfit_id, image_url, keyword, rating) VALUES (?, ?, ?, ?)",
        (req.outfit_id, req.image_url, req.keyword, req.rating)
    )
    conn.commit()
    conn.close()
    return {"message": "Rating saved"}

# ---------------- Train Endpoint ----------------
@app.post("/train")
def train_model():
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute("SELECT image_url, keyword, rating FROM ratings")
    data = c.fetchall()
    conn.close()

    if not data:
        return {"message": "No ratings to train on"}

    optimizer = AdamW(model.parameters(), lr=5e-6)

    for image_url, keyword, rating in data:
        try:
            response = requests.get(image_url, timeout=5)
            image = Image.open(BytesIO(response.content)).convert("RGB")

            inputs = processor(text=[keyword], images=image, return_tensors="pt", padding=True)
            inputs = {k: v.to(device) for k, v in inputs.items()}

            outputs = model(**inputs)

            logits_per_image = outputs.logits_per_image  # shape [1,1] if single text
            target = torch.tensor([[rating / 5.0]], device=device)

            loss = torch.nn.functional.mse_loss(logits_per_image, target)

            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
        except Exception as e:
            print(f"Skipping {image_url}: {e}")
            continue


    # Save fine-tuned model
    save_dir = "./fine_tuned_clip"
    model.save_pretrained(save_dir)
    processor.save_pretrained(save_dir)

    return {"message": "Model trained and saved", "save_dir": save_dir}

