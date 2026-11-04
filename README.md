# Teleprompter

App Android (Kotlin + Jetpack Compose + CameraX) para grabar videos por tomas leyendo el guion en la misma pantalla, junto a la cámara. Está conectada a **OS CRM** (Grabaciones de ELROI): cada quien ve los guiones que le tocan grabar y cada toma se sube sola al CRM.

## Cómo se usa

1. Descarga el APK más reciente en **Releases** e instálalo.
2. Abre la app → **Ajustes** → inicia sesión con tu correo y contraseña del CRM, o toca **Vincular con QR** y escanea el QR de tu perfil del CRM (Perfil › App de grabación › «Vincular la app con QR»). Ese QR también se puede escanear con la cámara del teléfono.
3. Aparecen tus guiones: las piezas de Grabaciones por grabar («Mis reels» cuando te grabas tú, «Con equipo» en visitas). «Tuyo» marca las que tienes asignadas.
4. Toca un guion → se abre la cámara con la toma 1 arriba: el texto a decir en grande y, debajo, qué se ve y dónde va el celular.
5. Grabar → cuenta regresiva → se detiene sola al cumplir la duración → pasa a la siguiente toma.
6. Cada toma se sube al CRM por partes (si se va la señal, continúa donde se quedó, aunque cierres la app). Cuando el CRM la recibe completa, la toma queda hecha y se borra la copia del celular (en Ajustes puedes conservarla). Al subir la última toma, la pieza pasa a «Material subido».

Si repites una toma, el CRM guarda las dos versiones; el editor elige.

Controles: ◀ ▶ para cambiar de toma (o deslizar el texto), A−/A+ tamaño de letra, ⏱ auto/libre, ⟲ cambiar cámara.
Los botones de volumen (o un control Bluetooth de selfie) navegan tomas o graban, según Ajustes.

## Contrato con el CRM

Base: `https://oscrm.apps.elroi.cloud` (se cambia en Ajustes › Avanzado). Todas las llamadas llevan `Authorization: Bearer <token del dispositivo>` salvo login y vincular.

| Llamada | Para qué |
|---|---|
| `POST /api/v1/app/login` `{email, password, device}` | Inicia sesión; regresa `{token, user, device}` |
| `POST /api/v1/app/vincular` `{code, device}` | Igual, con el código del QR (`oscrm-teleprompter://vincular?url=…&code=…`, dura 10 min y sirve una vez) |
| `GET /api/v1/app/me` · `POST /api/v1/app/logout` | Quién soy · cerrar sesión |
| `GET /api/v1/app/guiones?modo=self_shoot\|onsite&client_id=` | `{guiones: [{id, titulo, cliente, modo, modo_label, fecha, mio, tomas: [{id, orden, texto, decir, escena, duracion, hecha, subidas}]}], clientes}` |
| `POST /api/v1/app/subidas` `{shoot_piece_id, shoot_take_id, file_name, size}` | Abre (o reanuda) la subida: `{uuid, offset, chunk_bytes}` |
| `POST /api/v1/app/subidas/{uuid}/partes?offset=N` (cuerpo = bytes) | Sube una parte; `409` trae el `offset` correcto; al final `{complete, take_done, piece_status}` |

El token solo sirve para la app (Grabaciones); se cierra desde el perfil del CRM.

### API propia (sin CRM)

Sin sesión del CRM se puede usar otra API en Ajustes › Avanzado:

- `GET {base}/guiones` → `{ "guiones": [ { "id": "...", "titulo": "...", "tomas": [ { "orden": 1, "texto": "...", "duracion": 4 } ] } ] }` (también arreglo directo o campos en inglés; ver `sample/guiones.json`).
- `POST {base}/guiones/{id}/tomas/{orden}/video` multipart con `video`, `guion_id`, `orden`, `texto`.

## Build

Cada push compila con GitHub Actions; solo `main` publica el APK en Releases (`teleprompter-vN.apk`).
La llave de firma **no está en el repo**: vive en los secretos de GitHub Actions (`TELEPROMPTER_KEYSTORE_B64`, `TELEPROMPTER_KEYSTORE_PASSWORD`, `TELEPROMPTER_KEY_ALIAS`, `TELEPROMPTER_KEY_PASSWORD`), así cada versión se instala encima de la anterior y nadie más puede firmar un APK que la reemplace. Sin esos secretos, `main` no publica.
