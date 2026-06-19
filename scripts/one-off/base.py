from playwright.sync_api import sync_playwright
import json

def scrape_magti_portal():
    with sync_playwright() as p:
        # ვქმნით სრულიად დამოუკიდებელ პროფილს bot_profile საქაღალდეში
        # ეს შენს ძირითად Chrome-ს არანაირად არ ეხება
        context = p.chromium.launch_persistent_context(
            user_data_dir="./bot_profile", 
            headless=False 
        )
        page = context.pages[0]
        
        print("ბოტი ხსნის საიტს...")
        page.goto("https://sites.google.com/view/magti-call-center")
        
        # სკრიპტი დაპაუზდება, სანამ შენ არ ეტყვი რომ გააგრძელოს
        input("\n>>> გაიარე ავტორიზაცია გახსნილ ფანჯარაში. როცა სტატიებს დაინახავ, მოდი აქ და დააჭირე ENTER-ს...\n")
        
        print("ვიწყებ მონაცემების ამოღებას...")
        articles = []
        
        sections = page.locator('div[role="main"] section').all()
        if not sections:
            sections = page.locator('.ty3Ipe, p, h2, h3').all()

        for i, section in enumerate(sections):
            text = section.inner_text().strip()
            if text and len(text) > 10:
                articles.append({
                    "id": i + 1,
                    "content": text
                })
        
        with open("old_news.json", "w", encoding="utf-8") as f:
            json.dump(articles, f, ensure_ascii=False, indent=4)
            
        print(f"მორჩა. ამოღებულია {len(articles)} ბლოკი. ფაილი შენახულია.")
        context.close()

if __name__ == "__main__":
    scrape_magti_portal()