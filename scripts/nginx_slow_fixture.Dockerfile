FROM python:3.11-alpine
COPY nginx_slow_fixture.py /fixture.py
USER 65534:65534
ENTRYPOINT ["python", "/fixture.py"]
