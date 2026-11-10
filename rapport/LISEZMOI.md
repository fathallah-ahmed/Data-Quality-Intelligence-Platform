# Rapport PFE — DQ-Insight (projet LaTeX / Overleaf)

Le rapport décrit **une seule version** du projet : la plateforme actuelle (Angular, Spring Boot, worker Airflow, Neo4j, service de questions GraphRAG).

## Compiler sur Overleaf
1. Overleaf > New Project > Upload Project > choisir `rapport_pfe_overleaf_v5.zip`
2. Menu > Compiler : pdfLaTeX ; Document principal : `main.tex`
3. Compiler deux fois (sommaire, listes des figures et tableaux).

Le rapport n'a pas pu être compilé localement (pas de LaTeX sur la machine de rédaction) : si Overleaf signale une erreur, indiquez la ligne.

## Structure
- `main.tex` : préambule (style, en-têtes, légendes) + ordre des parties
- `pages/` : page de garde (provisoire), dédicaces, remerciements, résumé, abstract, abréviations, bibliographie
- `chapitres/` : introduction, chapitre 1 à 8, conclusion
  1. Contexte général · 2. État de l'art et choix technologiques · 3. Analyse des besoins (7 epics, 29 récits)
  4. Conception · 5. Sprints 1–2 · 6. Sprints 3–4 · 7. Sprints 5–7 · 8. Évaluation et choix du modèle
- `figures/` : diagrammes générés et captures de l'application
- `mmd_sources/` : sources Mermaid des diagrammes (modifiables)

## Informations à compléter (en rouge dans le PDF)
Dans `main.tex`, section « Informations générales » : `\etudiant`, `\encadrantacad`, `\encadrantpro`, `\ecole`.
Le nom de la solution se change en une ligne : `\newcommand{\projet}{...}`.
Autres champs rouges : spécialité, département Wevioo, rôles Scrum, configuration matérielle, dates des sprints (Gantt),
résultats des tests fonctionnels (« à vérifier », chapitre 8), (tous les résultats du chapitre 8 sont mesurés : tests, trois modèles sur 19 questions).
Aucune mesure n'a été inventée : tout ce qui n'a pas été mesuré est laissé en rouge.

## Figures générées (remplaçables, même nom de fichier)
Diagrammes UML : `diag_classes_global`, `act_pipeline_global`, `arch_logicielle`, `arch_logique`, `arch_physique`, `pipeline_ia`,
`vue_generale_solution`, `uc_global`, `uc_deposer`, `uc_regles`, `uc_corriger`, `uc_questions`, `seq_profilage`,
`act_lecture_profilage`, `seq_depot`, `seq_reprise`, `act_roles`, `seq_analyse_table`, `seq_regles`, `seq_corrections`,
`seq_graphe`, `act_questions`, `seq_questions` et le graphique `eval_modeles` (comparaison des modèles).

Captures de l'application (prises sur l'application en fonctionnement, jeu de démonstration `customer-accounts`) :
`capture_accueil`, `capture_dashboard`, `capture_depot`, `capture_overview`, `capture_dimensions`, `capture_tables`,
`capture_model`, `capture_insights`, `capture_regles`, `capture_corrections`, `capture_graphe`, `capture_ask`.

## Figures à fournir (cadre rouge « FIGURE À INSÉRER » tant qu'elles manquent)
- `figures/logo_wevioo.png`, `figures/organigramme_wevioo.png` : logo et organigramme de Wevioo
- `figures/cycle_scrum.png` : cycle de vie Scrum
- `figures/capture_airflow.png` : interface d'Airflow, vue graphe d'une exécution réussie (neuf tâches)
- `figures/capture_neo4j.png` : graphe d'une version dans Neo4j Browser

## Logos des outils (tableau « Environnement logiciel », chapitre 5)
Déposez les logos (png) dans `figures/logos/` : `intellij`, `pycharm`, `vscode`, `spring`, `angular`, `python`, `ollama`,
`airflow`, `neo4j`, `docker`, `postgresql`, `pandas`, `sweetviz`, `powershell`, `git`.

## Remarque sur les captures
L'interface de l'application est en anglais ; les légendes du rapport sont en français.
