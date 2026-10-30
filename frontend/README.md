# Frontend Angular

Interface utilisateur de la plateforme Data Quality. Elle permet à l'admin d'uploader des bases CSV, suivre Airflow en temps réel, consulter les métriques, les insights LLM, les versions et le diagramme du modèle de données.

## Rôle

- Connexion admin avec HTTP Basic vers le backend.
- Upload multi-fichiers CSV.
- Ajout optionnel d'un contexte LLM global et/ou par table.
- Suppression d'un fichier avant upload.
- Détection visuelle d'une version déjà existante avec action "ouvrir" ou "relancer".
- Suivi de progression pendant le pipeline.
- Résultats progressifs dès qu'une table est prête.
- Dashboard global et table par table.
- Diagramme de modèle de données avec zoom, export image et mise en évidence des relations au survol.
- Suppression d'un dataset depuis l'interface.

## Prérequis

- Node.js et npm.
- Backend lancé sur `http://localhost:8081`.
- Airflow lancé indirectement via le backend.

## Lancer En Local

Depuis `<project path>\frontend` :

```powershell
npm install
npm start
```

URL :

```text
http://localhost:4200
```

Le serveur Angular recharge automatiquement l'application pendant le développement.

## Configuration API

L'URL API est actuellement définie dans `src/app/app.ts` :

```ts
private readonly apiBase = 'http://localhost:8081/api';
```

Si le backend change de port ou d'hôte, mets cette valeur à jour.

## Structure Utile

| Fichier/Dossier | Rôle |
| --- | --- |
| `src/app/app.ts` | Orchestration principale, appels API, SSE, état dashboard |
| `src/app/app.html` | Layout principal de l'application |
| `src/app/app.css` | Styles globaux du dashboard |
| `src/app/components/upload-panel` | Composant upload, contexte LLM, gestion fichiers |
| `src/app/components/dataset-sidebar` | Liste et suppression des datasets |
| `src/app/components/login-panel` | Formulaire de connexion admin |
| `src/app/models/data-quality.models.ts` | Types TypeScript des réponses backend |

## Commandes

```powershell
npm start
npm run build
npm test
```

## Workflow UI

1. Se connecter avec `admin / admin123` sauf si le backend utilise d'autres variables.
2. Choisir un ou plusieurs CSV.
3. Donner un nom au dataset.
4. Optionnel : ajouter contexte global, contexte par table et question métier.
5. Lancer `Upload and run`.
6. Suivre la progression; les tables prêtes apparaissent sans attendre la fin complète.
7. Consulter les métriques, LLM insights, modèle de données et comparaison de versions.

## Vérification Avant Push

```powershell
npm run build
git status --short
```

Ne commit pas :

- `node_modules/`
- `dist/`
- `.angular/`
- captures ou fichiers temporaires locaux.
