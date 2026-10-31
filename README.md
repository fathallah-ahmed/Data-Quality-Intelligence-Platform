# Plateforme Data Quality Wevioo

Plateforme d'analyse de qualité de données pour des bases CSV :

- **frontend** Angular : upload, suivi de l'exécution en temps réel, résultats par onglets (vue d'ensemble, tables, modèle de données, insights) ;
- **backend** Spring Boot : datasets et versions, authentification par jeton, API REST, événements temps réel (SSE), déclenchement et surveillance d'Airflow ;
- **worker** Python/Airflow : profiling des tables, métriques, relations entre tables, enrichissement LLM (Ollama) et envoi progressif des résultats au backend.

## Démarrage en une commande

Développement sous Windows : une fenêtre par service (Ollama, Airflow, backend, frontend), puis le navigateur s'ouvre quand tout répond.

```powershell
.\start-dev.ps1
```

Tout dans Docker (Airflow, backend, frontend ; Ollama reste sur la machine) :

```powershell
docker compose --env-file Data_Quality_Wevioo/.env up --build
```

Application : `http://localhost:4200` · API : `http://localhost:8081` · Airflow : `http://localhost:8080`. Le fichier `Data_Quality_Wevioo\.env` doit exister (copie `.env.example`). Le fichier `docker-compose.yml` racine inclut celui du worker : tous les services partagent un réseau et les mêmes secrets, et le backend appelle Airflow par son nom de service.

## Démarrage rapide

Sans le script : ouvre **quatre** terminaux PowerShell depuis la racine du projet, dans cet ordre.

### 1. Ollama (optionnel)

```powershell
ollama serve
ollama pull qwen2.5:14b-instruct
```

Sans Ollama, le pipeline fonctionne quand même : les insights sont alors calculés à partir des métriques et l'interface l'indique (« Deterministic only – Ollama unavailable »). Si `bind: Only one usage of each socket address` s'affiche, Ollama tourne déjà.

### 2. Airflow

```powershell
cd .\Data_Quality_Wevioo
Copy-Item .\.env.example .\.env -ErrorAction SilentlyContinue
docker compose up --build
```

- UI Airflow : `http://localhost:8080`
- Login : `admin` / valeur de `AIRFLOW_ADMIN_PASSWORD` dans `Data_Quality_Wevioo\.env`
- `PIPELINE_API_TOKEN` dans `.env` doit être identique côté backend (le script de lancement du backend le lit automatiquement).

### 3. Backend

```powershell
cd .\backend
.\run-backend.ps1
```

- API : `http://localhost:8081` — test : `Invoke-RestMethod http://localhost:8081/health`
- Java 17 requis : `winget install --id EclipseAdoptium.Temurin.17.JDK -e`

### 4. Frontend

```powershell
cd .\frontend
npm install
npm start
```

Ouvre `http://localhost:4200` et connecte-toi avec `admin / admin123` (valeurs de développement, voir *Sécurité*).

## Architecture

| Bloc | Dossier | Port | Rôle |
| --- | --- | --- | --- |
| Frontend Angular | `frontend` | 4200 | Interface, upload, tableaux de bord, modèle de données |
| Backend Spring Boot | `backend` | 8081 | API REST, sécurité, métadonnées, déclenchement Airflow, SSE, watchdog |
| Airflow | `Data_Quality_Wevioo` | 8080 | DAG `data_quality_analysis_pipeline` (phases Python) |
| Ollama | externe | 11434 | LLM local pour les interprétations |
| Base backend | `backend/data` | fichier H2 | Datasets, versions, résultats (MySQL possible) |
| Fichiers | `Data_Quality_Wevioo/data`, `storage` | disque | CSV uploadés, JSON de métriques et d'analyses |

```text
Navigateur -> Angular (4200) -> Spring Boot (8081, /api)
Spring Boot -> Airflow REST (8080) : déclenche le DAG après l'enregistrement de la version
Airflow -> Spring Boot /api/pipeline/{runId}/... : start, table-analysis, global-analysis, complete
Spring Boot -> Angular : SSE /api/events/stream (repli automatique en polling)
```

## Fonctionnement

1. L'admin se connecte (`POST /api/auth/login`, jeton JWT valable 8 h, gardé dans `sessionStorage`).
2. Il dépose un ou plusieurs CSV dans la fenêtre **New analysis** (une table par fichier).
3. Le backend valide tous les fichiers avant d'écrire quoi que ce soit, calcule un SHA-256 par fichier, crée la version, enregistre les fichiers, puis déclenche Airflow une fois la transaction validée.
4. Airflow exécute les phases visibles : validation, notification, découverte des tables, profiling, métriques + LLM, publication, analyse globale, clôture.
5. Chaque table apparaît dès que ses métriques sont prêtes, puis est enrichie par le LLM.
6. Le statut final est `COMPLETED`, `PARTIAL` (au moins une table en échec, avec la raison) ou `FAILED`. Un bouton **Retry** relance une version en échec ou partielle.
7. Les règles qualité du dataset sont évaluées sur la version terminée ; un échec déclenche une alerte (toast en direct, badge dans la liste des datasets, bandeau dans la vue d'ensemble).

### Onglets d'une version

| Onglet | Contenu |
| --- | --- |
| Overview | Statut du run, score et note A–D, complétude / unicité, santé de chaque table, colonnes à revoir, insights globaux |
| Tables | Profil d'une table : colonnes, valeurs fréquentes, valeurs manquantes, associations, insights |
| Data model | Diagramme des tables et relations (recouvrement de valeurs) |
| Insights | Analyse sémantique (rôles des colonnes, contrat de données, surveillance, causes, impacts) |
| Compare | Écarts avec une version plus ancienne : score, lignes, colonnes ajoutées/supprimées, changements de type, valeurs manquantes |
| Rules | Règles qualité du dataset et résultat pour cette version, avec des règles suggérées à partir des données |

Le bouton **Report** ouvre une page imprimable de la version (Imprimer / Enregistrer en PDF, ou téléchargement Markdown).

Raccourcis : `Ctrl/⌘ K` ou `/` (recherche et actions), `N` (nouvelle analyse), `1`–`6` (changer d'onglet).

### Règles qualité

Types : colonne sans valeur manquante, au plus X % de valeurs manquantes, valeurs uniques, au moins N lignes, au plus X % de doublons, score minimal, baisse maximale du nombre de lignes par rapport à la version précédente. Chaque règle est `WARNING` ou `CRITICAL`, peut être mise en pause, et visée sur une table (et une colonne). Elles sont évaluées à la fin de chaque run et à la demande (**Check now**).

### Reprise après un redémarrage

- Au démarrage, le backend interroge Airflow pour chaque run `PENDING`/`RUNNING` : un run inconnu d'Airflow est relancé (si ses fichiers sont toujours là), un run en échec côté Airflow est marqué `FAILED`, un run en cours est laissé tranquille.
- Les callbacks du worker (tables, analyse globale, fin de run) sont retentés pendant environ deux minutes : un redémarrage du backend n'interrompt pas un run en cours.
- Le watchdog continue de surveiller les runs bloqués.

### Statuts

| Objet | Valeurs |
| --- | --- |
| Run / version | `PENDING` (en file), `RUNNING`, `COMPLETED`, `PARTIAL`, `FAILED` |
| Table par rapport à la version précédente | `NEW`, `MODIFIED`, `UNCHANGED`, `REMOVED` |
| Statut LLM (`llmStatus`) | `ok`, `unavailable` (Ollama injoignable), `invalid` (réponse rejetée), `disabled`, `pending` |

- **Doublons** : un upload identique (mêmes noms de fichiers et même contenu SHA-256) propose d'ouvrir la version existante ou de la relancer. Un fichier modifié de même taille crée bien une nouvelle version.
- **Changements de table** : comparés par empreinte du contenu (`content_hash`) et du schéma ; une valeur modifiée donne `MODIFIED`. Les tables absentes de la nouvelle version sont listées `REMOVED`.
- **Score qualité** : par table, 100 − % moyen de valeurs manquantes − % de lignes dupliquées ; pour la version, moyenne pondérée par le nombre de lignes.
- **Watchdog** : toutes les minutes, les runs `PENDING`/`RUNNING` depuis plus de 10 min sont vérifiés auprès d'Airflow ; un run inconnu ou en échec dans Airflow passe en `FAILED` avec un message clair.

## API

Tous les endpoints `/api/**` (sauf login et callbacks pipeline) exigent `Authorization: Bearer <jeton>` et le rôle `ADMIN`.

| Méthode | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/health` | État du backend |
| `POST` | `/api/auth/login` | `{username, password}` → `{token, expiresAt, username, roles}` |
| `GET` | `/api/auth/me` | Utilisateur courant |
| `POST` | `/api/datasets/upload` | Upload multipart (`files`, `name`, `rerunExisting`, contexte LLM) ; renvoie aussi `skippedFiles` (fichiers vides) |
| `GET` | `/api/datasets` | Datasets (avec `versionCount` et dernière version) |
| `GET` | `/api/datasets/{id}/versions` | Versions d'un dataset |
| `GET` | `/api/datasets/{id}/versions/{versionId}` | Détails : tables, `failedTables`, `qualityScore`, analyse globale |
| `GET` | `/api/datasets/{id}/versions/{versionId}/tables/{tableVersionId}` | Une table avec ses métriques brutes (valeurs fréquentes, quartiles, associations) |
| `POST` | `/api/datasets/{id}/versions/{versionId}/rerun` | Relance l'analyse d'une version (409 si elle tourne déjà) |
| `GET` | `/api/datasets/{id}/versions/{versionId}/diff?against=` | Écarts avec la version précédente (ou `against`) : score, lignes, schéma par table |
| `GET` `POST` | `/api/datasets/{id}/rules` | Liste / création des règles qualité |
| `PUT` `DELETE` | `/api/datasets/{id}/rules/{ruleId}` | Modification (pause comprise) / suppression |
| `GET` | `/api/datasets/{id}/versions/{versionId}/rule-results` | Résultat des règles pour une version |
| `POST` | `/api/datasets/{id}/versions/{versionId}/rules/evaluate` | Réévalue les règles sur une version |
| `DELETE` | `/api/datasets/{id}` | Supprime le dataset et ses fichiers (409 pendant un run) |
| `GET` | `/api/events/stream` | Flux SSE |
| `POST` | `/api/pipeline/{runId}/start` \| `table-analysis` \| `global-analysis` \| `complete` | Callbacks Airflow (`X-Pipeline-Token`) |

Codes d'erreur : 400 requête invalide, 401 jeton absent/invalide (y compris jeton pipeline), 404 ressource ou route inconnue, 409 conflit, 413 upload trop gros, 500 erreur interne (message générique, détail dans les logs).

## Sécurité

- Les valeurs par défaut (`admin123`, `dev-pipeline-token`, `JWT_SECRET` de dev, mot de passe Airflow `admin`) sont réservées au développement : le backend affiche un **WARN** au démarrage s'il les utilise, et `STRICT_SECRETS=true` refuse de démarrer avec elles.
- Variables à définir avant tout partage : `ADMIN_PASSWORD`, `JWT_SECRET`, `PIPELINE_API_TOKEN`, `AIRFLOW_PASSWORD` (backend) et les secrets de `Data_Quality_Wevioo\.env`.
- Le port PostgreSQL d'Airflow n'est plus publié sur l'hôte.

## Intégration continue

`.github/workflows/ci.yml` exécute, à chaque push et pull request : les tests du backend (`./mvnw test`), le build et les tests du frontend, les tests Python du pipeline (avec les contraintes d'Airflow 2.10.0 / Python 3.12) et la validation du `docker-compose.yml`.

## Tests

```powershell
cd .\backend; .\mvnw.cmd test
cd .\frontend; npm run build; npm test
cd .\Data_Quality_Wevioo; .\venv\Scripts\python.exe -m pip install -r requirements-dev.txt; .\venv\Scripts\python.exe -m pytest
```

## Git

Le `.gitignore` racine exclut `.env`, `node_modules`, `dist`, `.angular`, `target`, `backend/data`, `Data_Quality_Wevioo/data`, `storage`, `logs`, `airflow.cfg`, `*.pid`, `*.db`. Vérifie `git status --short` avant un push.

READMEs détaillés : `frontend\README.md`, `backend\README.md`, `Data_Quality_Wevioo\README.md`.

## Dépannage

**`JAVA_HOME` non défini** — installe le JDK 17, rouvre PowerShell, puis si besoin :

```powershell
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1 -ExpandProperty FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
[Environment]::SetEnvironmentVariable('Path', "$jdk\bin;" + [Environment]::GetEnvironmentVariable('Path', 'User'), 'User')
```

**Airflow : login invalide** — le mot de passe vient de `AIRFLOW_ADMIN_PASSWORD`. Si le volume Postgres garde un ancien mot de passe :

```powershell
cd .\Data_Quality_Wevioo
$line = Get-Content .\.env | Where-Object { $_ -match '^AIRFLOW_ADMIN_PASSWORD=' } | Select-Object -First 1
docker compose exec airflow-webserver airflow users reset-password -u admin -p $line.Substring($line.IndexOf('=') + 1)
```

**Run en échec dès l'upload (« Could not start the analysis in Airflow »)** — Airflow n'est pas joignable ou refuse les identifiants (403). Relance le backend avec `.\run-backend.ps1` (il lit le mot de passe Airflow dans `.env`), puis si besoin `docker compose exec airflow-webserver airflow sync-perm`. Une fois Airflow disponible, clique **Retry**.

**Run qui reste « Queued »** — après 2 minutes l'interface affiche un lien vers Airflow. Vérifie que le DAG n'est pas en pause :

```powershell
docker compose exec airflow-webserver airflow dags unpause data_quality_analysis_pipeline
```
