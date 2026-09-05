> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# Seed guide

```powershell
# კომპანიის ორგი (default / რეკომენდებული)
venv\Scripts\python.exe scripts\seed_portal.py org

# მხოლოდ ~20 TEST_LOGINS
venv\Scripts\python.exe scripts\seed_portal.py users

# მსუბუქი news/video
venv\Scripts\python.exe scripts\seed_portal.py demo
```

იხ. [`TEST_LOGINS.md`](../TEST_LOGINS.md).
