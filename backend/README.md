V# Backend Spring Boot

Backend REST de la plateforme Data Quality. Il reçoit les uploads, versionne les datasets, déclenche Airflow, stocke les résultats et protège l'accès avec un rôle admin.

## Rôle

- Upload de un ou plusieurs fichiers CSV.
- Création de datasets et versions.
- Détection de version déjà existante sans hash complet du dataset.
- Stockage des métriques table/global et interprétations LLM.
- Déclenchement du DAG Airflow `data_quality_analysis_pipeline`.
- Réception des callbacks Airflow.
- Publication temps réel via SSE.
- Suppression complète d'un dataset et de ses artefacts.

## Prérequis

- Java 17.
- Maven wrapper inclus : `mvnw.cmd`.
- Airflow disponible sur `http://localhost:8080` pour lancer le pipeline.
- Frontend autorisé par CORS sur `http://localhost:4200`.

Installer Java 17 si nécessaire :

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK -e
java -version
```

## Lancer En Local

Depuis `C:\Users\ahmed\Documents\Codex\2026-06-30\t\work\backend` :

```powershell
.\run-backend.ps1
```

Le script lit automatiquement `..\Data_Quality_Wevioo\.env` et injecte :

- `AIRFLOW_USERNAME`
- `AIRFLOW_PASSWORD`
- `PIPELINE_API_TOKEN`
- `JAVA_HOME` si la session PowerShell ne l'a pas encore rechargé

Commande manuelle équivalente :

```powershell
$airflowEnv = Get-Content ..\Data_Quality_Wevioo\.env | Where-Object { $_ -match '^AIRFLOW_ADMIN_PASSWORD=' } | Select-Object -First 1
$pipelineEnv = Get-Content ..\Data_Quality_Wevioo\.env | Where-Object { $_ -match '^PIPELINE_API_TOKEN=' } | Select-Object -First 1
$env:AIRFLOW_USERNAME='admin'
$env:AIRFLOW_PASSWORD=$airflowEnv.Substring($airflowEnv.IndexOf('=') + 1)
$env:PIPELINE_API_TOKEN=$pipelineEnv.Substring($pipelineEnv.IndexOf('=') + 1)
.\mvnw.cmd spring-boot:run
```

URL backend :

```text
http://localhost:8081
```

Health check :

```powershell
Invoke-RestMethod http://localhost:8081/health
```

## Configuration

Les valeurs par défaut sont dans `src/main/resources/application.properties`.

| Variable | Défaut | Description |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | Port du backend |
| `DB_URL` | H2 fichier `./data/dq-platform` | Base de métadonnées |
| `DB_USERNAME` | `sa` | Utilisateur DB |
| `DB_PASSWORD` | vide | Mot de passe DB |
| `DB_DRIVER` | `org.h2.Driver` | Driver JDBC |
| `JPA_DDL_AUTO` | `update` | Gestion schéma Hibernate |
| `MAX_UPLOAD_SIZE` | `500MB` | Taille max upload |
| `APP_STORAGE_ROOT` | `../Data_Quality_Wevioo/data/uploads` | Où le backend écrit les CSV pour Airflow |
| `AIRFLOW_BASE_URL` | `http://localhost:8080` | URL REST Airflow |
| `AIRFLOW_USERNAME` | `admin` | Utilisateur Airflow |
| `AIRFLOW_PASSWORD` | `admin` | Mot de passe Airflow; en local, utiliser la valeur de `Data_Quality_Wevioo\.env` |
| `AIRFLOW_DAG_ID` | `data_quality_analysis_pipeline` | DAG à déclencher |
| `PIPELINE_BACKEND_URL` | `http://host.docker.internal:8081` | URL backend vue depuis Airflow Docker |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://127.0.0.1:4200` | Origines frontend |
| `ADMIN_USERNAME` | `admin` | Login admin |
| `ADMIN_PASSWORD` | `admin123` | Mot de passe admin |
| `PIPELINE_API_TOKEN` | `dev-pipeline-token` | Token partagé avec Airflow |

Exemple MySQL :

```powershell
$env:DB_URL='jdbc:mysql://localhost:3306/dq_platform?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='your-password'
$env:DB_DRIVER='com.mysql.cj.jdbc.Driver'
.\mvnw.cmd spring-boot:run
```

## Sécurité

Le backend utilise HTTP Basic.

Compte par défaut :

```text
admin / admin123
```

Endpoints protégés par `ROLE_ADMIN` :

- `/api/auth/**`
- `/api/datasets/**`
- `/api/events/**`

Endpoints pipeline :

- `/api/pipeline/**` est accessible sans session admin, mais exige le header `X-Pipeline-Token`.

## Endpoints Principaux

| Méthode | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/health` | Vérifie que le backend répond |
| `GET` | `/api/auth/me` | Retourne l'utilisateur courant et ses rôles |
| `POST` | `/api/datasets/upload` | Upload CSV et déclenche Airflow |
| `GET` | `/api/datasets` | Liste les datasets |
| `GET` | `/api/datasets/{datasetId}/versions` | Liste les versions |
| `GET` | `/api/datasets/{datasetId}/versions/{versionId}` | Détails d'une version |
| `DELETE` | `/api/datasets/{datasetId}` | Supprime dataset, versions et artefacts |
| `GET` | `/api/events/stream` | Flux SSE temps réel |
| `POST` | `/api/pipeline/{runId}/start` | Callback Airflow démarrage |
| `POST` | `/api/pipeline/{runId}/table-analysis` | Callback résultat table |
| `POST` | `/api/pipeline/{runId}/global-analysis` | Callback analyse globale |
| `POST` | `/api/pipeline/{runId}/complete` | Callback fin pipeline |

## Upload Multipart

`POST /api/datasets/upload`

Paramètres possibles :

- `name` : nom du dataset.
- `rerunExisting` : `true` pour relancer une version déjà détectée.
- `contextScope` : portée du contexte LLM.
- `globalDescription` : description globale de la base.
- `globalQuestion` : question métier globale.
- `tableDescriptionsJson` : descriptions/questions par table.
- `files` : plusieurs fichiers CSV.
- `file` : ancien mode avec un seul fichier.

## Vérification Avant Push

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
git status --short
```

Ne commit pas :

- `target/`
- `data/`
- secrets ou variables locales.

## je remercie hadhemi sioud omi el ghalia al majhoudetha m3aya malgre que ena rasi msa3sa3
