# Séance 10 — Sécurité et interface Angular

**Objectif :** comprendre comment l'accès est protégé de bout en bout (serveur et interface) et comment l'interface gère son état avec des signaux, sans que l'écran « saute ».

**Durée :** 2 h · **Prérequis :** séances 0, 6 et 9.

Fichiers :
- Serveur (`backend/src/main/java/wevioo/backend/`) : `config/SecurityConfig.java`, `config/SecretsGuard.java`, `controller/AuthController.java`, `service/TokenService.java`, `config/AppProperties.java`.
- Interface (`frontend/src/app/`) : `app.routes.ts`, `app.config.ts`, `core/auth.service.ts`, `core/auth.guard.ts`, `core/auth.interceptor.ts`, `core/workspace.store.ts`, `core/live-events.service.ts`, `core/api.service.ts`.

---

## 1. Les trois « jetons » du projet

Il y a trois secrets différents, à ne pas confondre :

| Jeton | Qui le présente à qui | En-tête | Vérifié par |
| --- | --- | --- | --- |
| **JWT de l'utilisateur** | navigateur → serveur | `Authorization: Bearer …` | Spring Security |
| **Jeton du pipeline** | worker Airflow → serveur | `X-Pipeline-Token` | `PipelineController.requirePipelineToken` |
| **Jeton du service de questions** | serveur → service de questions | `X-QA-Token` | `main.py` (`hmac.compare_digest`) |

Plus deux mots de passe de service : Airflow (`AIRFLOW_PASSWORD`) et Neo4j (`NEO4J_PASSWORD`).

---

## 2. La connexion

### `POST /api/auth/login` (`AuthController`)
1. Refuse un identifiant vide ou un mot de passe absent (« Enter a username and a password »).
2. Demande à l'`AuthenticationManager` de vérifier l'identifiant et le mot de passe.
3. Si c'est bon : `TokenService.issue` fabrique un **JWT** ; sinon **401** « Invalid username or password » (le même message pour un identifiant inconnu et un mauvais mot de passe : on ne dit pas lequel est faux).

### L'utilisateur : `SecurityConfig.userDetailsService`
Un **seul compte en mémoire** (`InMemoryUserDetailsManager`) : identifiant et mot de passe lus dans `ADMIN_USERNAME` / `ADMIN_PASSWORD` (par défaut `admin` / `admin123`, **valeurs de développement**), avec le rôle `ADMIN`. Le mot de passe est **haché** à la création avec `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (bcrypt par défaut) : il n'est jamais comparé en clair.

### Le jeton : `TokenService.issue`
Un **JWT** signé **HS256** contenant : l'émetteur `dq-platform`, le sujet (identifiant), la date d'émission, la date d'**expiration** (`JWT_TTL_MINUTES`, **480 min = 8 h**), et la liste des rôles dans la revendication `roles`. La réponse renvoie le jeton, son expiration, l'identifiant et les rôles.

### La clé de signature : `jwtKey()`
HS256 exige une clé de 256 bits : le code **hache le secret configuré** (`JWT_SECRET`) avec SHA-256, ce qui permet d'utiliser un secret de n'importe quelle longueur.

> **Pourquoi un JWT ?** Il est **sans état** : le serveur ne garde aucune session ; il suffit de vérifier la signature et la date d'expiration. D'où `SessionCreationPolicy.STATELESS`.

---

## 3. Les règles d'accès : `SecurityConfig.securityFilterChain`

Dans l'ordre (la première règle qui correspond s'applique) :

| Requête | Règle |
| --- | --- |
| `OPTIONS /**` | libre (requêtes de pré-vérification du navigateur, CORS) |
| `/health` | libre |
| `/api/pipeline/**` | libre **pour Spring**, mais chaque route exige `X-Pipeline-Token` (séance 0) |
| `POST /api/auth/login` | libre |
| `/api/**` | rôle **`ADMIN`** |
| tout le reste | authentifié |

Autres choix :
- **CSRF désactivé** : une attaque CSRF repose sur un cookie envoyé automatiquement par le navigateur ; ici le jeton est envoyé **à la main** dans un en-tête, donc ce risque n'existe pas.
- **CORS** activé : seules les origines listées dans `CORS_ALLOWED_ORIGINS` (par défaut `localhost:4200`) peuvent appeler l'API depuis un navigateur.
- `oauth2ResourceServer(jwt)` : le serveur valide lui-même les JWT reçus ; `jwtAuthenticationConverter` lit la revendication `roles` et ajoute le préfixe `ROLE_` pour obtenir `ROLE_ADMIN`.

---

## 4. Les secrets par défaut : `SecretsGuard`

Au démarrage, `defaultSecretsInUse` détecte si `ADMIN_PASSWORD` vaut `admin123`, `PIPELINE_API_TOKEN` vaut `dev-pipeline-token`, `JWT_SECRET` commence par `dev-jwt-secret`, ou `AIRFLOW_PASSWORD` vaut `admin` (ou une valeur « placeholder » vide ou commençant par `change-this`). Deux comportements :
- par défaut : un **avertissement bien visible** dans les journaux (une bande d'étoiles) ;
- avec `STRICT_SECRETS=true` : le serveur **refuse de démarrer**.

> **À dire en soutenance :** les valeurs par défaut sont faites pour le développement ; la plateforme **signale** qu'elle les utilise et peut **refuser de démarrer** avec elles. Pour une mise en production, on les change toutes.

---

## 5. Les protections qui entourent le serveur

| Menace | Protection (où) |
| --- | --- |
| Deviner le jeton du pipeline ou des questions par le temps de réponse | comparaison **en temps constant** (`MessageDigest.isEqual`, `hmac.compare_digest`) |
| Accès au service de questions depuis le navigateur | il n'est appelé que par le serveur ; le navigateur n'a pas son jeton |
| Lecture de la base d'Airflow | le port de PostgreSQL n'est pas publié sur l'hôte |
| Nom de fichier malveillant (`../../x.csv`) | `safeFilename` (séance 1) |
| Suppression d'un mauvais dossier | `deleteDirectory` refuse tout hors de la racine autorisée (séance 1) |
| Requête Cypher qui écrit | garde-fous + `EXPLAIN` (séance 9) |
| Fuite de secrets | `.env` jamais versionné (`.gitignore`) |

---

## 6. L'interface : la connexion

### `auth.service.ts`
- **Session** : `{token, expiresAt, username, roles}`, stockée dans **`sessionStorage`** (vidée à la fermeture de l'onglet), jamais le mot de passe.
- `login` : appelle l'API ; **refuse un compte qui n'a pas le rôle `ADMIN`** (« This account is not an administrator »), enregistre la session.
- `isAuthenticated` (un `computed`) : vrai si une session existe **et** que la date d'expiration est **dans le futur**.
- `readSession` : relit la session au démarrage ; une session expirée est **supprimée**.
- `logout(returnUrl)` : efface la session et envoie sur `/login`, en gardant la page d'origine.

### `auth.interceptor.ts`
Un intercepteur HTTP qui s'exécute **sur chaque appel** : il ajoute `Authorization: Bearer <jeton>` aux appels vers l'API (pas à la connexion). Si le serveur répond **401** alors qu'on se croit connecté, il affiche « Your session has expired. Please sign in again. » et déconnecte.

### `auth.guard.ts` et `app.routes.ts`
- `authGuard` : si non connecté, redirige vers `/login?returnUrl=<page demandée>` ; après connexion on y revient.
- `guestGuard` : sur `/login`, si on est déjà connecté, renvoie vers `/datasets`.
- **Routes publiques** : `/` (page d'accueil) et `/login`. **Tout le reste** est dans un bloc `canActivate: [authGuard]` (écran « shell » et ses enfants : `datasets`, `datasets/:id`, `…/versions/:v/:tab`, `…/report`).
- Les composants sont chargés **à la demande** (`loadComponent` avec `import(...)`) : le navigateur ne télécharge un écran que lorsqu'on y va.
- Les **garde-fous de l'interface ne sont pas la sécurité** : ils améliorent l'expérience. La vraie protection est **côté serveur** (`/api/** → ADMIN`). Quelqu'un qui contournerait l'interface se heurterait au 401.

---

## 7. L'état de l'interface : les signaux

`app.config.ts` : `provideZonelessChangeDetection()` : **pas de zone.js**. Angular ne vérifie plus toute l'application à chaque événement : il ne met à jour que ce qui dépend d'un **signal** qui a changé.

| Concept | Rôle | Exemple |
| --- | --- | --- |
| `signal(x)` | une valeur observable ; `.set()` / `.update()` la change | `readonly details = signal<VersionDetails \| null>(null)` |
| `computed(() => …)` | une valeur **dérivée**, recalculée seulement si ses signaux changent | `sortedDatasets`, `isAuthenticated` |
| `effect` | une action déclenchée quand des signaux changent | (animations, synchronisations) |
| `inject(Service)` | récupérer un service | `inject(ApiService)` |

### `WorkspaceStore` : l'état partagé (`core/workspace.store.ts`)
Un service unique (`providedIn: 'root'`) qui contient : la liste des jeux, la **sélection** (jeu, version, table), les **détails** de la version, des indicateurs de chargement et d'erreur. Les composants **lisent** ces signaux et appellent des **méthodes** (`open`, `refresh`, `selectTable`) : ils ne gèrent pas eux-mêmes les appels HTTP des données partagées.

Trois mécanismes à comprendre :

1. **Le compteur de requêtes (`requestId`)**. `loadDetails` fait `const request = ++this.requestId` ; quand la réponse arrive, `if (request !== this.requestId) return`. Si l'utilisateur a changé de version entre-temps, la réponse **périmée est ignorée** : on évite d'afficher les données de la mauvaise version (*race condition*).
2. **Le rechargement silencieux (`silent`)**. Pour un rechargement dû à un événement, on **ne vide pas l'écran** : l'ancienne donnée reste affichée jusqu'à l'arrivée de la nouvelle, donc la page ne « saute » pas.
3. **L'écoute des événements** (`onLiveEvents`). `bufferTime(400)` regroupe les événements par **rafales de 400 ms** : une rafale = **un seul** rechargement. Selon l'événement : un simple `POLL` ne recharge la version ouverte que si elle est **en cours** (`PENDING`/`RUNNING`) ; un événement qui **concerne la sélection** ou `RECONNECTED` recharge la liste des versions et les détails ; la liste des jeux est rechargée dans tous les cas.

Le détail d'une table (`tableDetail`) n'est chargé que **pour la table sélectionnée**, et rechargé seulement si elle a changé (`tableChanged` compare identifiant, statut du modèle, lignes et score).

### `LiveEventsService` (séances 0 et 6)
SSE par `fetch` (pour envoyer le jeton), repli sur le polling toutes les 5 s, événement `RECONNECTED` après coupure.

### Les routes portent l'état
L'URL contient **tout** : `/datasets/2/versions/2/tables?table=accounts`. `withComponentInputBinding()` lie les paramètres de la route aux **entrées** des composants (`datasetId`, `versionId`, `tab`, `table`). Un lien partagé ou un rechargement rouvre le même écran.

---

## 8. Mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| JWT | Jeton signé contenant des revendications (qui, rôles, expiration). |
| Sans état (*stateless*) | Le serveur ne garde aucune session entre deux requêtes. |
| HS256 | Signature symétrique (HMAC-SHA256) avec une clé secrète. |
| CSRF | Attaque exploitant un cookie envoyé automatiquement ; sans objet avec un jeton en en-tête. |
| CORS | Règle du navigateur : quelles origines peuvent appeler l'API. |
| Intercepteur | Code exécuté sur chaque appel HTTP. |
| Garde de route | Fonction qui autorise ou redirige une navigation. |
| Signal / `computed` | Valeur réactive / valeur dérivée réactive. |
| Zoneless | Angular sans `zone.js` : il ne met à jour que ce qui dépend d'un signal modifié. |
| Chargement paresseux | Télécharger un écran seulement quand on y va. |

---

## 9. Exercices

**A. Décoder un jeton (10 min).** Connectez-vous (`curl`, comme en séance 0), copiez le jeton, collez-le dans une page de décodage JWT locale ou, mieux, dans la console du navigateur :
```javascript
JSON.parse(atob(token.split('.')[1]))
```
Quelles revendications voyez-vous (`iss`, `sub`, `iat`, `exp`, `roles`) ? Calculez la durée entre `iat` et `exp` : 8 heures ?

**B. Un appel sans jeton (10 min).**
`curl.exe -s -o NUL -w "%{http_code}" http://localhost:8081/api/datasets` → code ? Avec un jeton **modifié d'un caractère** ? Avec un bon jeton ?
*(Attendu : 401 sans jeton et avec un jeton altéré ; 200 avec un bon jeton.)*

**C. Le jeton du pipeline (10 min).** `curl.exe -s -X POST http://localhost:8081/api/pipeline/x/start -H "X-Pipeline-Token: faux" -d "{}"` → code ? *(Attendu : 401 « Invalid pipeline token ».)*

**D. Les gardes de routes (10 min).** Ouvrez une fenêtre privée sur `http://localhost:4200/datasets/1/versions/1/overview` : où êtes-vous redirigé et avec quel `returnUrl` ? Connectez-vous : où arrivez-vous ?

**E. Expiration (15 min).** Dans les outils du navigateur (onglet Application → Session Storage → `dq_session`), changez `expiresAt` pour une date passée et rafraîchissez : que se passe-t-il ?

**F. Voir un signal en action (15 min).** Ouvrez deux onglets sur la même version. Déposez un fichier dans l'un : l'autre se met-il à jour ? Pourquoi (quel service, quel événement) ?

**G. Lire un test (15 min).** `frontend/src/app/core/api.service.spec.ts` et les tests des gardes de routes : quel comportement de la section 6 chacun vérifie-t-il ?

---

## 10. Questions de contrôle

1. Quels sont les trois jetons du projet et qui présente chacun à qui ?
2. Pourquoi un JWT et pas une session côté serveur ?
3. Que contient le JWT émis ? Quelle est sa durée de vie ?
4. Comment est stocké le mot de passe de l'administrateur côté serveur ?
5. Pourquoi le CSRF est-il désactivé sans danger ici ?
6. Que fait `SecretsGuard`, et que change `STRICT_SECRETS=true` ?
7. Pourquoi les routes `/api/pipeline/**` sont-elles « libres » dans Spring ?
8. Où l'interface stocke-t-elle la session, et pourquoi pas `localStorage` ?
9. Les gardes de routes de l'interface suffisent-ils à protéger les données ?
10. À quoi sert `requestId` dans `WorkspaceStore` ?
11. Qu'est-ce qu'une mise à jour « silencieuse » et pourquoi ?
12. Qu'est-ce que « zoneless » apporte ?

### Corrections
1. JWT (navigateur → serveur, `Authorization: Bearer`), jeton du pipeline (worker → serveur, `X-Pipeline-Token`), jeton des questions (serveur → service de questions, `X-QA-Token`).
2. Le serveur ne garde aucune session : il vérifie la signature et l'expiration ; plus simple à déployer et à répartir sur plusieurs instances.
3. Émetteur `dq-platform`, sujet (identifiant), dates d'émission et d'expiration, rôles ; 8 heures (`JWT_TTL_MINUTES = 480`).
4. Haché (bcrypt par défaut, via le codeur délégant de Spring) ; jamais comparé en clair.
5. Le CSRF exploite un cookie envoyé automatiquement ; ici le jeton est envoyé à la main dans un en-tête.
6. Il avertit si un secret de développement est utilisé ; avec `STRICT_SECRETS=true`, le serveur refuse de démarrer.
7. Parce qu'elles ont leur propre authentification, par jeton partagé, vérifiée dans le contrôleur (pas de session utilisateur côté worker).
8. Dans `sessionStorage` : vidé à la fermeture de l'onglet, donc moins exposé qu'un stockage durable.
9. Non : ils améliorent l'expérience ; la vraie protection est côté serveur (`/api/** → ADMIN`), qui répond 401 sans jeton valide.
10. À ignorer une réponse périmée quand l'utilisateur a changé de version entre l'appel et la réponse.
11. Un rechargement qui garde l'ancienne donnée à l'écran jusqu'à l'arrivée de la nouvelle ; la page ne saute pas.
12. Angular ne revérifie plus toute l'application à chaque événement : seuls les composants dont un signal a changé sont mis à jour.

---

## 11. Votre bilan

Si vous avez fait toutes les séances, vous savez maintenant raconter :
1. **le chemin d'un fichier** (séance 0) ;
2. **le serveur** (1) et **le pipeline** (2) ;
3. **comment le code décide des rôles et mesure** (3) ;
4. **comment le modèle est contrôlé** (4) ;
5. **le score et les règles** (5) ;
6. **ce qui se passe quand ça tombe en panne** (6) ;
7. **les corrections** (7), **le graphe** (8), **les questions** (9) ;
8. **la sécurité et l'interface** (10).

Reprenez maintenant [questions-jury.md](questions-jury.md) et répondez aux 40 questions à voix haute.
