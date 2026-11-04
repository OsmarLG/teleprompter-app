# Teleprompter

App Android (Kotlin + Jetpack Compose + CameraX) para grabar videos por tomas leyendo el guion en la misma pantalla, junto a la cámara.

## Cómo se usa

1. Descarga el APK más reciente en **Releases** e instálalo.
2. Abre la app. Sin configurar nada aparece un guion de demo.
3. En **Ajustes** pon la URL base de tu API (y token si lleva).
4. Toca un guion → se abre la cámara con la toma 1 arriba.
5. Grabar → cuenta regresiva → se detiene sola al cumplir la duración → pasa a la siguiente toma.
6. Cada toma se guarda en `Movies/Teleprompter/<guion>/` y, si está activado, se sube a la API.

Controles: ◀ ▶ para cambiar de toma (o deslizar el texto), A−/A+ tamaño de letra, ⏱ auto/libre, ⟲ cambiar cámara.
Los botones de volumen (o un control Bluetooth de selfie) navegan tomas o graban, según Ajustes.

## Contrato de la API

URL base configurable, por ejemplo `https://mi-api.com/api`.

### `GET {base}/guiones`

Regresa la lista de guiones (ver `sample/guiones.json`). Formatos aceptados:

```json
{ "guiones": [ { "id": "...", "titulo": "...", "tomas": [ { "orden": 1, "texto": "...", "duracion": 4 } ] } ] }
```

También acepta un arreglo directo `[ {...} ]`, o los campos en inglés (`title`, `takes`, `text`, `duration`, `order`).
`duracion` en segundos; `0` u omitido = sin límite. Campos extra (`fecha_publicacion`, `plataforma`, etc.) se ignoran.

### `POST {base}/guiones/{id}/tomas/{orden}/video`

`multipart/form-data` con:

| campo      | tipo   |
|------------|--------|
| `video`    | archivo mp4 |
| `guion_id` | texto  |
| `orden`    | número |
| `texto`    | texto de la toma |

Responder `2xx` si se guardó. Si repites una toma, llega otro POST para el mismo `orden`: la API decide si reemplaza o guarda versiones.

Si hay token en Ajustes, ambas llamadas llevan `Authorization: Bearer <token>`.

Para pruebas sin API, la URL puede apuntar a un `.json` estático (solo lectura, sin subida).

## Build

Cada push a `main` compila con GitHub Actions y publica el APK en Releases (`teleprompter-vN.apk`).
La firma usa `app-debug.keystore` del repo para que cada versión se instale encima de la anterior.
