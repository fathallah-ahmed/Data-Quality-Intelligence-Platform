# Plateforme Data Quality Wevioo

Ce dossier contient la plateforme complète d'analyse de qualité de données :

- un frontend Angular pour uploader des bases CSV, suivre l'exécution et consulter les résultats;
- un backend Spring Boot qui gère les datasets, versions, authentification, API REST et événements temps réel;
- un worker Python/Airflow qui profile les tables, calcule les métriques, lance l'enrichissement LLM et renvoie les résultats au backend.

## How To Run - Quick Start

Lance le projet avec quatre terminaux PowerShell.

### Terminal 1 - Ollama

```powershell
ollama serve
```

Si tu vois une erreur de type `bind: Only one usage of each socket address`, Ollama tourne déjà sur le port `11434`.

### Terminal 2 - Airflow

```powershell
cd <project path>
Copy-Item .\.env.example .\.env -ErrorAction SilentlyContinue
docker compose up --build
```

Airflow UI :

```text
http://localhost:8080
```

Login Airflow :

```text
username: admin
password: valeur de AIRFLOW_ADMIN_PASSWORD dans Data_Quality_Wevioo\.env
```

### Terminal 3 - Backend Spring Boot

```powershell
cd <project path>\backend
.\run-backend.ps1
```

Backend :

```text
http://localhost:8081
```

Test rapide :

```powershell
Invoke-RestMethod http://localhost:8081/health
```

Si tu as l'erreur `The JAVA_HOME environment variable is not defined correctly`, installe un JDK 17 puis rouvre PowerShell :

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK -e
java -version
```

### Terminal 4 - Frontend Angular

```powershell
cd <project path>\frontend
npm install
npm start
```

Frontend :

```text
http://localhost:4200
```

Login application :

```text
admin / admin123
```

Ordre recommandé : Ollama, Airflow, backend, frontend. Ensuite ouvre `http://localhost:4200`, connecte-toi, upload les CSV, et le backend déclenchera Airflow automatiquement.

## Architecture Locale

| Bloc | Dossier | Port local | Rôle |
| --- | --- | --- | --- |
| Frontend Angular | `frontend` | `4200` | Interface utilisateur, upload, dashboard, diagramme de modèle de données |
| Backend Spring Boot | `backend` | `8081` | API REST, sécurité admin, stockage des métadonnées, déclenchement Airflow, SSE |
| Airflow | `Data_Quality_Wevioo` | `8080` | DAG `data_quality_analysis_pipeline` et phases Python |
| Ollama | externe | `11434` | Modèle LLM local quand l'enrichissement LLM est activé |
| Base backend | `backend/data` par défaut | fichier H2 | Métadonnées datasets/versions/résultats; MySQL possible via variables |
| Stockage fichiers | `Data_Quality_Wevioo/data` et `Data_Quality_Wevioo/storage` | disque local | CSV uploadés, JSON de métriques, analyses, artefacts pipeline |

Flux principal :

```text
Utilisateur
  -> Angular http://localhost:4200
  -> Spring Boot http://localhost:8081/api
  -> Airflow REST http://localhost:8080/api/v1/dags/data_quality_analysis_pipeline/dagRuns
  -> DAG Python
  -> Backend callbacks /api/pipeline/{runId}/...
  -> Angular via SSE /api/events/stream ou rechargement API
```

## Prérequis

- Docker Desktop avec WSL2 actif pour Airflow.
- Java 17 pour le backend.
- Node.js et npm pour Angular.
- Ollama si tu veux les réponses LLM locales.
- Optionnel : MySQL si tu ne veux pas utiliser H2.

## Démarrage Complet

Ouvre trois terminaux PowerShell depuis `<project path>`.

### 1. Lancer Ollama

```powershell
ollama serve
```

Si Windows dit que le port `11434` est déjà utilisé, Ollama tourne déjà. Tu peux vérifier avec :

```powershell
Get-Process ollama
```

### 2. Lancer Airflow

```powershell
cd .\Data_Quality_Wevioo
Copy-Item .\.env.example .\.env -ErrorAction SilentlyContinue
docker compose up --build
```

Airflow UI : `http://localhost:8080`

Login Airflow :

```text
username: admin
password: valeur de AIRFLOW_ADMIN_PASSWORD dans Data_Quality_Wevioo\.env
```

Important : la valeur `PIPELINE_API_TOKEN` dans `Data_Quality_Wevioo\.env` doit être la même que celle du backend.

### 3. Lancer le backend

```powershell
cd .\backend
.\run-backend.ps1
```

Backend : `http://localhost:8081`

Compte admin par défaut :

```text
admin / admin123
```

### 4. Lancer le frontend

```powershell
cd .\frontend
npm install
npm start
```

Frontend : `http://localhost:4200`

## Workflow Fonctionnel

1. L'utilisateur se connecte avec le rôle `ADMIN`.
2. Il upload un ou plusieurs fichiers CSV.
3. Le backend crée ou réutilise le dataset, crée une version, sauvegarde les fichiers et déclenche Airflow.
4. Airflow exécute les phases visibles dans l'UI Airflow :
   - validation de la requête backend;
   - notification de démarrage;
   - découverte des tables CSV;
   - profiling;
   - extraction des métriques et analyse LLM table par table;
   - publication progressive des résultats;
   - analyse globale;
   - clôture du pipeline.
5. Le backend reçoit les callbacks pipeline et publie les changements au frontend via SSE.
6. Le frontend affiche les résultats dès qu'ils arrivent : métriques, insights LLM, diagrammes, tables, versioning et statut.

## Cas Gérés

- Upload multi-tables : plusieurs CSV dans une même version.
- Même dataset sans changement : le backend détecte une version déjà existante via signature légère des fichiers et propose de l'ouvrir ou de relancer.
- Relance volontaire : `rerunExisting=true` permet de réexécuter une version existante.
- Résultats progressifs : les tables prêtes sont visibles sans attendre toute la base.
- Suppression dataset : le backend supprime les métadonnées et les artefacts locaux liés au dataset.
- Sécurité simple : un seul utilisateur admin avec rôle `ADMIN`; les endpoints dataset/event/auth nécessitent ce rôle.
- Pipeline interne : les callbacks Airflow utilisent `X-Pipeline-Token`.

## Préparer Pour Git

Le fichier `.gitignore` racine évite d'ajouter les sorties locales si tu initialises Git au niveau `work`.

Avant un push :

```powershell
git status --short
```

Ne commit pas :

- `.env` et secrets;
- `node_modules`, `dist`, `.angular`;
- `target`, `backend/data`;
- `Data_Quality_Wevioo/data`, `storage`, `logs`, `airflow.cfg`, `*.pid`, `*.db`;
- fichiers générés par les tests ou uploads locaux.

Les README spécifiques sont ici :

- `frontend\README.md`
- `backend\README.md`
- `Data_Quality_Wevioo\README.md`

## Dépannage Rapide

### Backend: JAVA_HOME non défini

Symptôme :

```text
The JAVA_HOME environment variable is not defined correctly
```

Correction recommandée :

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK -e
```

Ferme puis rouvre PowerShell, puis vérifie :

```powershell
java -version
```

Si `java` n'est toujours pas trouvé, configure `JAVA_HOME` :

```powershell
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1 -ExpandProperty FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
[Environment]::SetEnvironmentVariable('Path', "$jdk\bin;" + [Environment]::GetEnvironmentVariable('Path', 'User'), 'User')
```

Ensuite ferme et rouvre PowerShell.

### Airflow: invalid login

Le login est `admin`, mais le mot de passe vient de `Data_Quality_Wevioo\.env` :

```text
AIRFLOW_ADMIN_PASSWORD=...
```

Si Airflow garde un ancien mot de passe à cause du volume Postgres déjà existant :

```powershell
cd <project path>
$pwdLine = Get-Content .\.env | Where-Object { $_ -match '^AIRFLOW_ADMIN_PASSWORD=' } | Select-Object -First 1
$airflowPassword = $pwdLine.Substring($pwdLine.IndexOf('=') + 1)
docker compose exec airflow-webserver airflow users reset-password -u admin -p $airflowPassword
```

### Frontend: Upload saved, but Airflow trigger failed 403

Cause fréquente : le backend tourne avec le mot de passe Airflow par défaut `admin`, alors que ton Airflow utilise `AIRFLOW_ADMIN_PASSWORD` dans `Data_Quality_Wevioo\.env`.

Stoppe le backend avec `Ctrl+C`, puis relance-le comme ceci :

```powershell
cd <project path>\backend
.\run-backend.ps1
```

Si Airflow continue à répondre `403`, resynchronise les permissions :

```powershell
cd <project path>
docker compose exec airflow-webserver airflow sync-perm
```

Si le run reste bloqué en `queued`, vérifie que le DAG n'est pas en pause :

```powershell
docker compose exec airflow-webserver airflow dags unpause data_quality_analysis_pipeline
```
