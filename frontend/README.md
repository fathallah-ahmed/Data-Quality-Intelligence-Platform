# Frontend Angular

Interface de la plateforme Data Quality : connexion, upload de bases CSV, suivi du pipeline en temps réel et consultation des résultats par onglets.

## Lancer en local

```powershell
npm install
npm start
```

Application : `http://localhost:4200` (backend attendu sur `http://localhost:8081`).

## Commandes

```powershell
npm start        # serveur de développement
npm run build    # build de production (dist/frontend)
npm test         # tests unitaires (Vitest)
```

## Configuration

Les URLs sont dans `src/environments/` :

| Fichier | Utilisé par | Contenu |
| --- | --- | --- |
| `environment.ts` | `npm start` | `apiBase`, `airflowUiUrl`, `maxUploadBytes` |
| `environment.prod.ts` | `npm run build` | mêmes clés, à adapter au déploiement |

## Navigation

| URL | Écran |
| --- | --- |
| `/login` | Connexion (jeton JWT gardé dans `sessionStorage`) |
| `/datasets` | Ouvre le dataset le plus récent, ou l'état vide avec « New analysis » |
| `/datasets/:id/versions/:versionId/overview` | Statut du run (étapes, durée, erreurs, Retry), KPI, changements, insights globaux |
| `.../tables?table=orders` | Liste des tables + détail (colonnes, valeurs fréquentes, valeurs manquantes, associations, insights) |
| `.../model` | Modèle de données (zoom, déplacement, export SVG, relations avec couverture) |
| `.../insights?table=orders` | Analyse sémantique par groupes repliables + recommandations globales |
| `.../compare?against=<versionId>` | Écarts avec une version plus ancienne (score, lignes, colonnes, valeurs manquantes) |
| `.../rules` | Règles qualité du dataset, résultat pour la version, règles suggérées |
| `.../report` | Rapport imprimable de la version (PDF via l'impression, ou Markdown) |

L'URL contient toute la sélection : un lien partagé ou un rafraîchissement rouvre le même dataset, la même version, le même onglet et la même table.

## Structure

| Dossier | Rôle |
| --- | --- |
| `src/app/core` | `ApiService` (tous les appels HTTP), `AuthService` + guard + intercepteur (jeton, 401), `LiveEventsService` (SSE puis polling), `WorkspaceStore` (état partagé en signals), thème, toasts |
| `src/app/features` | Écrans : `login`, `shell` (barre du haut, panneau latéral, palette de commandes), `datasets`, `version`, `overview`, `tables`, `data-model`, `insights`, `compare`, `rules`, `report`, `upload` |
| `src/app/shared` | Formatage (`format.ts`), lecture des réponses LLM (`insight.ts`), pipes, composants communs (badges, état vide, modal, confirmation, onglets, toasts, skeleton, info-bulle) |
| `src/styles.css` | Design system : variables (couleurs, espacements, rayons, ombres), thème sombre, boutons, badges, cartes, tableaux, formulaires |

Les composants sont `OnPush` et l'application tourne sans zone.js : les valeurs dérivées sont des `computed()`, pas des appels de méthodes dans les templates.

## Accessibilité et responsive

- Focus visible, libellés sur tous les champs, toasts dans une région `aria-live`, navigation clavier dans les onglets (flèches) et la liste des tables.
- Thème clair/sombre selon le système, bouton de bascule mémorisé.
- De 360 px à 1 920 px ; sous 1 024 px la liste des datasets devient un tiroir ; les tableaux larges défilent dans leur carte.
