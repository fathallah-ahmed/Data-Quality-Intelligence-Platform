# Frontend Angular

Interface de la plateforme Data Quality : connexion, upload de bases CSV, suivi du pipeline en temps réel, et consultation des résultats par onglets (qualité, règles, corrections, graphe, questions).

Pile : Angular 21, composants autonomes `OnPush`, application **sans zone.js** (signals), Vitest pour les tests, nginx pour la production.

## Lancer en local

```powershell
npm install
npm start
```

Application : `http://localhost:4200`. Le backend est attendu sur `http://localhost:8081` ; connexion avec `admin / admin123` (valeurs de développement du backend).

## Commandes

```powershell
npm start        # serveur de développement
npm run build    # build de production (dist/frontend)
npm test         # tests unitaires (Vitest, sans mode watch)
```

## Configuration

Les URLs sont dans `src/environments/` :

| Fichier | Utilisé par | Contenu |
| --- | --- | --- |
| `environment.ts` | `npm start` | `apiBase`, `airflowUiUrl`, `maxUploadBytes` |
| `environment.prod.ts` | `npm run build` et l'image Docker | mêmes clés, **fixées au moment du build** : adaptez-les à l'endroit où les services sont déployés (par défaut `http://localhost:8081/api` et `http://localhost:8080`) |

`maxUploadBytes` (500 Mo) doit rester aligné sur `MAX_UPLOAD_SIZE` du backend.

## Docker

`Dockerfile` : build de production (`npm ci` puis `ng build`) servi par nginx (`nginx.conf` : fichiers hachés mis en cache un an, tout le reste renvoyé à `index.html` pour que les liens profonds appartiennent au routeur Angular). Le `package-lock.json` doit rester complet pour `npm ci` sous Linux : après une mise à jour des dépendances, régénérez-le dans un conteneur Linux plutôt que sous Windows seul.

## Navigation

| URL | Écran |
| --- | --- |
| `/login` | Connexion (jeton JWT gardé dans `sessionStorage`) |
| `/datasets` | Ouvre le dataset le plus récent, ou l'accueil avec « New analysis » quand il n'y en a pas |
| `/datasets/:id/versions/:versionId/overview` | Statut du run (étapes, durée, erreurs, Retry), score et note, dimensions, changements, insights globaux |
| `.../tables?table=orders` | Liste des tables + détail (colonnes et leur rôle, valeurs fréquentes, valeurs manquantes, associations, insights) |
| `.../model` | Modèle de données (zoom, déplacement, export SVG, relations avec couverture et cardinalité) |
| `.../insights?table=orders` | Analyse sémantique par groupes repliables + recommandations globales |
| `.../compare?against=<versionId>` | Écarts avec une version plus ancienne (score, lignes, colonnes, valeurs manquantes) |
| `.../rules` | Règles qualité du dataset, résultat pour la version, règles suggérées à approuver |
| `.../corrections` | Corrections d'une table : trouvées ou écrites en langage naturel, aperçu chiffré, application sur une copie |
| `.../graph` | Modèle de graphe proposé, noms par l'IA, construction et suppression dans Neo4j |
| `.../ask` | Questions en langage naturel sur les données, la qualité et l'usage de l'application |
| `.../report` | Rapport imprimable de la version (PDF via l'impression, ou Markdown) |

L'URL contient toute la sélection : un lien partagé ou un rafraîchissement rouvre le même dataset, la même version, le même onglet et la même table.

Raccourcis : `Ctrl/⌘ K` ou `/` ouvre la palette de commandes (datasets, tables, onglets, actions), `N` ouvre « New analysis », `1` à `9` changent d'onglet de la version. Les raccourcis sont ignorés pendant la saisie ou quand un dialogue est ouvert.

## Écrans notables

- **New analysis** : dépôt de plusieurs CSV (un fichier de même nom remplace le précédent, les fichiers invalides sont signalés), nom du dataset, **domaine, objectif et ce que représente une ligne**, et un contexte libre (global ou par table). Un upload identique à une version existante propose de l'ouvrir ou de la relancer.
- **Overview** : progression `Upload → Airflow → Tables x/y → Global → Done`, avec les tables en échec et leur raison, et Retry.
- **Rules** : approbation des règles proposées à partir de l'objectif, création de règles (format, ordre de deux dates, seuils…), « Check now ».
- **Corrections** : une liste de corrections se construit depuis ce que l'application trouve ou depuis une phrase ; un aperçu chiffré se met à jour à chaque changement ; l'application crée une **nouvelle version**.
- **Graph** : les constats (conflits, tables isolées, liens incertains) sont listés avant la construction ; après la construction, une requête prête à coller dans Neo4j Browser, le nombre d'autres graphes présents, et « Delete this graph » (deux clics).
- **Ask** : des exemples de questions à cliquer ; chaque réponse dit d'où elle vient (graphe, résultats qualité, guide) et, pour le graphe, montre la requête et les lignes.
- Alerte en direct (toast) quand des règles échouent à la fin d'un run, où que soit l'utilisateur.

## Structure

| Dossier | Rôle |
| --- | --- |
| `src/app/core` | `ApiService` (tous les appels HTTP), `AuthService` + guard + intercepteur (jeton, 401), `LiveEventsService` (SSE par `fetch`, puis polling toutes les 5 s), `WorkspaceStore` (état partagé en signals), thème, mise en page, toasts, modèles de données (`models.ts`) |
| `src/app/features` | un dossier par écran : `login`, `shell` (barre du haut, panneau latéral, palette de commandes), `datasets`, `version` (hôte des onglets), `overview`, `tables`, `data-model`, `insights`, `compare`, `rules`, `corrections`, `graph`, `ask`, `report`, `upload` |
| `src/app/shared` | formatage (`format.ts`), lecture des réponses du modèle (`insight.ts`), pipes, icônes, raccourcis clavier, composants communs (badges, état vide, modal, confirmation, onglets, anneau de score, skeleton, info-bulle) |
| `src/styles.css` | design system : variables (couleurs, espacements, rayons, ombres), thème sombre, boutons, badges, cartes, tableaux, formulaires |

Les valeurs dérivées sont des `computed()`, pas des appels de méthodes dans les templates. Les rafraîchissements silencieux (événements live, polling) gardent les données à l'écran jusqu'à l'arrivée des nouvelles, pour que la page ne saute pas.

## Tests

`npm test` lance 48 tests : connexion et appels d'API, lecture des événements SSE, formatage, notes et dimensions de qualité, rapport, règles, modèle de données, profil d'une table, sélection de fichiers à l'upload, ordre des constats du graphe et petits utilitaires de l'onglet Ask.

## Accessibilité et responsive

- Focus visible, libellés sur tous les champs, toasts dans une région `aria-live`, navigation clavier dans les onglets (flèches) et la liste des tables.
- Thème clair/sombre selon le système, bouton de bascule mémorisé.
- De 360 px à 1 920 px ; sous 1 024 px la liste des datasets devient un tiroir ; les tableaux larges défilent dans leur carte.
