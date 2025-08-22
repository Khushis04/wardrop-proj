package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5/pgxpool"
)

var pool *pgxpool.Pool

type Clothing struct {
	ID       int    `json:"id"`
	Category string `json:"category"`
	Color    string `json:"color"`
	Material string `json:"material"`
	Occasion string `json:"occasion"`
}

type RecommendationRequest struct {
	Occasion string   `json:"occasion"`
	Category string   `json:"category,omitempty"`
	Color    string   `json:"color,omitempty"`
	Material string   `json:"material,omitempty"`
	Keywords []string `json:"keywords,omitempty"`
}

type RecommendationResponse struct {
	Recommendation string `json:"recommendation"`
	Weather        string `json:"weather,omitempty"`
	Debug          string `json:"debug,omitempty"`
}

type OutfitItem struct {
	ID       int      `json:"id"`
	Color    string   `json:"color"`
	Material string   `json:"material"`
	ImageURL string   `json:"image_url"`
	Labels   []string `json:"labels,omitempty"`
}

type OutfitResponse struct {
	OutfitID string                 `json:"outfit_id,omitempty"`
	Weather  string                 `json:"weather"`
	Occasion string                 `json:"occasion"`
	Items    map[string]*OutfitItem `json:"items"`
}

type RatingRequest struct {
	OutfitID string         `json:"outfit_id"`
	Rating   int            `json:"rating"`
	Items    map[string]int `json:"items,omitempty"`
	UserID   string         `json:"user_id,omitempty"`
}

var localIP string

func getLocalIP() (string, error) {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "", err
	}
	for _, addr := range addrs {
		ipNet, ok := addr.(*net.IPNet)
		if !ok || ipNet.IP.IsLoopback() {
			continue
		}
		ip := ipNet.IP.To4()
		if ip == nil {
			continue
		}
		// Ignore link-local 169.254.x.x
		if ip[0] == 169 && ip[1] == 254 {
			continue
		}
		return ip.String(), nil
	}
	return "", fmt.Errorf("no connected network interface found")
}

func main() {
	dsn := "postgres://khushi:password123@localhost:5432/wardrobe"
	var err error
	pool, err = pgxpool.New(context.Background(), dsn)
	if err != nil {
		log.Fatal("Unable to connect to database: ", err)
	}
	defer pool.Close()

	fmt.Println("Connected to PostgreSQL!")

	localIP, err = getLocalIP()
	if err != nil {
		log.Println("Could not detect local IP:", err)
		localIP = "localhost"
	} else {
		fmt.Println("Derived local IP address:", localIP)
	}

	app := fiber.New(fiber.Config{
		ReadTimeout:  5 * time.Minute,
		WriteTimeout: 5 * time.Minute,
		BodyLimit:    20 * 1024 * 1024, // 20 MB
	})

	app.Static("/uploads", "./uploads")

	// Route: Add clothing (use localIP for URL generation inside handler)
	app.Post("/clothes", addClothing)

	app.Get("/images/:id", func(c *fiber.Ctx) error {
		id := c.Params("id")
		var imageURL string

		err := pool.QueryRow(context.Background(),
			"SELECT image_url FROM clothes WHERE id=$1", id).Scan(&imageURL)
		if err != nil {
			log.Println("Image URL not found for ID:", id, "err:", err)
			return c.Status(404).SendString("Image not found")
		}
		return c.JSON(fiber.Map{"image_url": imageURL})
	})

	app.Get("/clothes", getClothes)

	app.Delete("/clothes/:id", func(c *fiber.Ctx) error {
		id := c.Params("id")
		log.Printf("Delete requested for ID: %s\n", id)
		cmdTag, err := pool.Exec(context.Background(), "DELETE FROM clothes WHERE id=$1", id)
		if err != nil {
			log.Println("Delete error:", err)
			return c.Status(500).SendString("Failed to delete clothing")
		}
		if cmdTag.RowsAffected() == 0 {
			log.Printf("Delete failed: no clothing with ID %s found\n", id)
			return c.Status(404).SendString("Clothing not found")
		}
		return c.SendStatus(204)

	})

	app.Post("/recommendation", getRecommendation)

	app.Post("/rate", rateHandler)

	// GET /recommendation?occasion=party&category=dress&color=red&material=cotton&keywords=casual,elegant
	app.Get("/recommendation", func(c *fiber.Ctx) error {
		type RecommendationRequest struct {
			Occasion string   `query:"occasion"`
			Category string   `query:"category"`
			Color    string   `query:"color"`
			Material string   `query:"material"`
			Keywords []string `query:"keywords"`
		}

		var req RecommendationRequest
		if err := c.QueryParser(&req); err != nil {
			return c.Status(400).JSON(fiber.Map{
				"error": "Invalid query parameters",
			})
		}

		if raw := c.Query("keywords"); raw != "" && len(req.Keywords) == 0 {
			req.Keywords = strings.Split(raw, ",")
		}

		recommendation := fmt.Sprintf(
			"Recommendation for occasion=%s, category=%s, color=%s, material=%s, keywords=%v",
			req.Occasion, req.Category, req.Color, req.Material, req.Keywords,
		)

		return c.JSON(fiber.Map{
			"recommendation": recommendation,
		})
	})

	// Start server on all interfaces
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	log.Fatal(app.Listen("0.0.0.0:" + port))
}

func rateHandler(c *fiber.Ctx) error {
	var r RatingRequest
	if err := c.BodyParser(&r); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "invalid body"})
	}
	if r.Rating < 1 || r.Rating > 5 {
		return c.Status(400).JSON(fiber.Map{"error": "rating must be 1..5"})
	}

	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		log.Printf("[ERROR] begin tx: %v", err)
		return c.Status(500).SendString("db error")
	}
	defer tx.Rollback(ctx)

	// Insert a rating row for each clothing id in the outfit (so we can aggregate per item)
	for _, cid := range r.Items {
		_, err := tx.Exec(ctx,
			`INSERT INTO item_ratings (outfit_id, clothing_id, rating, user_id) VALUES ($1,$2,$3,$4)`,
			r.OutfitID, cid, r.Rating, r.UserID,
		)
		if err != nil {
			log.Printf("[ERROR] insert rating: %v", err)
			return c.Status(500).SendString("db error")
		}
	}

	if err := tx.Commit(ctx); err != nil {
		log.Printf("[ERROR] commit: %v", err)
		return c.Status(500).SendString("db error")
	}

	return c.JSON(fiber.Map{"status": "ok"})
}

// helper — deterministic hash id derived from the selected items (slot:id)
func generateOutfitID(items map[string]*OutfitItem) string {
	// collect sorted slot:id pairs
	slots := make([]string, 0, len(items))
	for s := range items {
		slots = append(slots, s)
	}
	sort.Strings(slots)

	pairs := make([]string, 0, len(slots))
	for _, s := range slots {
		it := items[s]
		if it == nil {
			continue
		}
		pairs = append(pairs, fmt.Sprintf("%s:%d", s, it.ID))
	}

	data := strings.Join(pairs, "|")
	sum := sha256.Sum256([]byte(data))
	return hex.EncodeToString(sum[:])
}

func getRecommendation(c *fiber.Ctx) error {
	var req RecommendationRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Invalid request"})
	}

	if req.Occasion == "" {
		return c.Status(400).JSON(fiber.Map{"error": "Occasion is required"})
	}

	weather, err := fetchWeather("London")
	if err != nil {
		log.Printf("[WARN] Could not fetch weather: %v", err)
		weather = "Unknown"
	}

	recommendation := generateAIRecommendation(req, weather)

	// ✅ Return structured JSON, not stringified JSON
	return c.JSON(recommendation)
}

func getImageKeywordScore(imageURL string, keywords []string) (map[string]float64, error) {
	reqBody := map[string]interface{}{
		"image_url": imageURL,
		"keywords":  keywords,
	}
	bodyBytes, _ := json.Marshal(reqBody)

	resp, err := http.Post("http://localhost:8000/analyze", "application/json", bytes.NewReader(bodyBytes))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var scores map[string]float64
	if err := json.NewDecoder(resp.Body).Decode(&scores); err != nil {
		return nil, err
	}
	return scores, nil
}

func getAvgRating(ctx context.Context, clothingID int) float64 {
	var avg float64
	err := pool.QueryRow(ctx, "SELECT COALESCE(AVG(rating),0) FROM item_ratings WHERE clothing_id=$1", clothingID).Scan(&avg)
	if err != nil {
		log.Printf("[WARN] getAvgRating error for clothingID=%d: %v", clothingID, err)
		return 0.0
	}
	return avg
}

func generateAIRecommendation(req RecommendationRequest, weather string) OutfitResponse {
	ctx := context.Background()

	if req.Keywords == nil {
		req.Keywords = []string{}
	}

	// Map of slot → category name
	slots := map[string]string{
		"top":      "top",
		"bottom":   "bottom",
		"dress":    "dress",
		"jacket":   "jacket",
		"footwear": "footwear",
	}

	outfit := make(map[string]*OutfitItem)

	for slot, category := range slots {
		// Build query
		query := `SELECT id, color, material, image_url 
          		FROM clothes 
          		WHERE occasion ILIKE $1`
		args := []interface{}{req.Occasion}

		if category != "" {
			query += " AND category ILIKE $" + fmt.Sprint(len(args)+1)
			args = append(args, category) // exact (case-insensitive) match
		}
		if req.Color != "" {
			query += " AND color ILIKE $" + fmt.Sprint(len(args)+1)
			args = append(args, "%"+req.Color+"%") // partial, case-insensitive
		}
		if req.Material != "" {
			query += " AND material ILIKE $" + fmt.Sprint(len(args)+1)
			args = append(args, "%"+req.Material+"%") // partial, case-insensitive
		}

		query += " ORDER BY random() LIMIT 5"

		// Log the built query + args to verify what’s being executed
		log.Printf("[SQL][%s] %s | args=%v", slot, query, args)

		rows, err := pool.Query(ctx, query, args...)
		if err != nil {
			log.Printf("[ERROR] DB query failed for slot '%s': %v", slot, err)
			continue // skip adding this slot
		}
		defer rows.Close()

		var (
			bestItem  *OutfitItem
			bestScore = -1.0
			rowCount  = 0
		)

		for rows.Next() {
			rowCount++

			var item OutfitItem
			if err := rows.Scan(&item.ID, &item.Color, &item.Material, &item.ImageURL); err != nil {
				log.Printf("[WARN] Scan failed for slot '%s': %v", slot, err)
				continue
			}

			score := 1.0
			if len(req.Keywords) > 0 {
				if scores, err := getImageKeywordScore(item.ImageURL, req.Keywords); err == nil {
					for _, s := range scores {
						score += s
					}
				}
				item.Labels = req.Keywords
			}
			// add rating bonus (scale factor)
			avg := getAvgRating(ctx, item.ID) // 0.0..5.0
			if avg > 0 {
				score += 0.25 * avg // tweak weight (0.25*5.0 = +1.25 max)
			}

			if score > bestScore {
				bestScore = score
				cp := item
				bestItem = &cp
			}
		}

		if err := rows.Err(); err != nil {
			log.Printf("[WARN] Row iteration error for slot '%s': %v", slot, err)
		}

		log.Printf("[DEBUG] Slot '%s' matched %d rows; picked=%v", slot, rowCount, bestItem != nil)

		if bestItem != nil {
			outfit[slot] = bestItem
			log.Printf("[DEBUG] Selected for slot '%s': %+v", slot, bestItem)
		} else {
			// skip empty slot entirely
			log.Printf("[DEBUG] No suitable item found for slot '%s'", slot)
		}

		countSQL := `SELECT COUNT(*) FROM clothes WHERE occasion ILIKE $1 AND category ILIKE $2`
		var n int
		if err := pool.QueryRow(ctx, countSQL, req.Occasion, category).Scan(&n); err == nil {
			log.Printf("[DEBUG] Slot '%s' precheck count=%d", slot, n)
		}

	}

	outfitID := generateOutfitID(outfit)
	return OutfitResponse{
		OutfitID: outfitID,
		Weather:  weather,
		Occasion: req.Occasion,
		Items:    outfit,
	}

}

// ----- Weather fetch -----
func fetchWeather(city string) (string, error) {
	apiKey := os.Getenv("OPENWEATHER_API_KEY")
	if apiKey == "" {
		return "No API key provided", fmt.Errorf("missing OPENWEATHER_API_KEY")
	}

	url := fmt.Sprintf("https://api.openweathermap.org/data/2.5/weather?q=%s&appid=%s&units=metric",
		city, apiKey)

	resp, err := http.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	body, _ := ioutil.ReadAll(resp.Body)

	if resp.StatusCode != 200 {
		return "", fmt.Errorf("weather API error: %s", string(body))
	}

	var data map[string]interface{}
	if err := json.Unmarshal(body, &data); err != nil {
		return "", err
	}

	weatherArr := data["weather"].([]interface{})
	weatherMain := weatherArr[0].(map[string]interface{})["main"].(string)
	temp := data["main"].(map[string]interface{})["temp"].(float64)

	return fmt.Sprintf("%s, %.1f°C", weatherMain, temp), nil
}

func addClothing(c *fiber.Ctx) error {
	fmt.Println("[addClothing] Received request")

	form, err := c.MultipartForm()
	if err != nil {
		return c.Status(400).SendString("Invalid form data")
	}

	category := getField(form.Value, "category")
	color := getField(form.Value, "color")
	material := getField(form.Value, "material")
	occasion := getField(form.Value, "occasion")

	if len(form.File["image"]) == 0 {
		return c.Status(400).SendString("Image file is required")
	}

	fileHeader := form.File["image"][0]

	// Save to ./uploads
	savePath := fmt.Sprintf("./uploads/%d_%s", time.Now().Unix(), fileHeader.Filename)
	if err := c.SaveFile(fileHeader, savePath); err != nil {
		return c.Status(500).SendString("Failed to save image")
	}

	// Generate URL
	const backendIP = "192.168.226.253"
	imageURL := fmt.Sprintf("http://%s:8080/uploads/%s", backendIP, filepath.Base(savePath))

	query := `
        INSERT INTO clothes ( category, color, material, occasion, image_url)
        VALUES ($1, $2, $3, $4, $5)
        RETURNING id
    `
	var id int
	err = pool.QueryRow(context.Background(), query,
		category, color, material, occasion, imageURL,
	).Scan(&id)

	if err != nil {
		log.Println("DB insert error:", err)
		return c.Status(500).SendString("Failed to insert clothing")
	}

	return c.JSON(fiber.Map{
		"message":   "Clothing added successfully",
		"id":        id,
		"image_url": imageURL,
	})
}

func getClothes(c *fiber.Ctx) error {
	occasions := c.Query("occasion")
	preferences := c.Query("preferences")

	baseQuery := `
        SELECT id, category, color, material, occasion, image_url 
        FROM clothes
    `
	var conditions []string
	var args []interface{}

	if occasions != "" {
		vals := strings.Split(occasions, ",")
		conditions = append(conditions, fmt.Sprintf("occasion = ANY($%d::text[])", len(args)+1))
		args = append(args, vals)
	}

	if preferences != "" {
		vals := strings.Split(preferences, ",")
		conditions = append(conditions, fmt.Sprintf("color = ANY($%d::text[])", len(args)+1))
		args = append(args, vals)
	}

	if len(conditions) > 0 {
		baseQuery += " WHERE " + strings.Join(conditions, " AND ")
	}

	fmt.Printf("[getClothes] Executing query: %s with args=%v\n", baseQuery, args)

	rows, err := pool.Query(context.Background(), baseQuery, args...)
	if err != nil {
		log.Println("Failed to fetch clothes:", err)
		return c.Status(500).SendString("Failed to fetch clothes")
	}
	defer rows.Close()

	var result []fiber.Map
	for rows.Next() {
		var (
			id int

			category string
			color    string
			material string
			occasion string
			imageURL string
		)

		if err := rows.Scan(&id, &category, &color, &material, &occasion, &imageURL); err != nil {
			log.Println("Row scan error:", err)
			return c.Status(500).SendString("Failed to read row")
		}

		result = append(result, fiber.Map{
			"id":        id,
			"category":  category,
			"color":     color,
			"material":  material,
			"occasion":  occasion,
			"image_url": imageURL,
		})
	}

	fmt.Printf("Retrieved %d clothing items\n", len(result))
	return c.JSON(result)
}

// Helper to safely read form values
func getField(values map[string][]string, key string) string {
	if v, ok := values[key]; ok && len(v) > 0 {
		return v[0]
	}
	return ""
}
