# Cartes NNN — appli Android

Le topic NNN de jeuxvideo.com avec le jeu de cartes de Balade92 intégré, sans Tampermonkey.

- L'appli ouvre le topic NNN. On se connecte à son compte jeuxvideo.com une fois, puis on poste et on joue comme sur PC (bouton du jeu, boosters, classeur, duels).
- C'est exactement le même jeu que le script du PC, avec le même serveur Firebase : on joue en ligne contre les joueurs PC.
- **Le jeu se met à jour tout seul** : à chaque lancement, l'appli regarde la version du script sur `Rigodrolax/TCG-NNN` et la télécharge si elle est plus récente. Pas besoin de réinstaller l'appli pour une nouvelle version du jeu.

## Installer

1. Sur ton téléphone, ouvre ce lien : **https://github.com/Rigodrolax/TCG-NNN-apk/releases/latest/download/CartesNNN.apk** (toujours la dernière version).
2. Ouvre le fichier. Si Android le demande, autorise l'installation depuis ton navigateur ou ton gestionnaire de fichiers.
3. Lance « Cartes NNN » et connecte-toi à ton compte jeuxvideo.com.

Tes cartes du PC ne sont pas dans l'appli au départ : sur le PC, onglet Boosters, « 💾 Télécharger ma sauvegarde », envoie-toi le fichier, puis dans l'appli « 📂 Restaurer ».

## Publier une nouvelle version de l'appli

Seulement si l'appli elle-même change (pas pour une nouvelle version du jeu) : modifier `version.txt`. GitHub construit l'APK, la signe et la publie dans une Release.

## Clé de signature (une seule fois)

Dans **Settings → Secrets and variables → Actions**, créer :
- `NNN_KEYSTORE_B64` : contenu de `NNN_KEYSTORE_B64.txt`
- `NNN_KEYSTORE_PASS` : contenu de `NNN_KEYSTORE_PASS.txt`

Ne jamais mettre ces fichiers sur GitHub et ne jamais les perdre : sans cette clé, impossible d'installer une mise à jour par-dessus l'appli.
