# 📘 Guide Utilisateur - BthExplorer

**BthExplorer** est une solution hybride (Android + Cloud) conçue pour la détection, le suivi et l'analyse environnementale des périphériques Bluetooth Low Energy (BLE).

---

## 🚀 Cas d'Usage et Applications

BthExplorer n'est pas qu'un simple scanner ; il transforme votre smartphone en une sonde de collecte de données. Voici où il devient indispensable :

### 1. Sécurité Informatique (Pentesting & Audit)
*   **Détection d'appareils espions :** Identifier des balises (AirTags, trackers) ou des caméras cachées utilisant le BLE.
*   **Audit de périmètre :** Vérifier quels appareils d'entreprise (PC, imprimantes, casques) diffusent des informations sensibles (Nom, Services) sans protection.
*   **Shadow IoT :** Recenser les appareils personnels non autorisés connectés au réseau physique de l'entreprise.

### 2. Administration Réseau & Maintenance
*   **Inventaire de flotte :** Recenser automatiquement un parc de casques (ex: Jabra), de capteurs ou d'équipements médicaux via leurs numéros de série et modèles (DIS).
*   **Diagnostic de connectivité :** Analyser la puissance du signal (RSSI) pour identifier les zones d'interférence ou les batteries faibles sur des capteurs IoT.

### 3. Recensement et Urbanisme (Smart City)
*   **Mesure de flux :** Estimer la fréquentation d'un lieu public en comptant le nombre d'adresses MAC uniques détectées sur une période donnée.
*   **Cartographie du signal :** Utiliser les données GPS pour créer une carte thermique (Heatmap) de la présence d'objets connectés dans une zone géographique.

### 4. Logistique et Asset Tracking
*   **Localisation d'actifs :** Retrouver un équipement perdu dans un entrepôt en utilisant l'estimation de distance et la dernière position GPS connue sur Firebase.
*   **Historique de détection :** Savoir à quelle heure précise un équipement est passé à proximité d'un agent de scan.

---

## 🛠 Fonctionnalités Clés

### 📡 Scan en Temps Réel
*   **Indicateur de présence :** Un point vert indique que l'appareil a été vu il y a moins de 15 secondes.
*   **Estimation de distance :** Calculée en mètres basé sur la puissance du signal (RSSI).
*   **Tri intelligent :** Classez les appareils par puissance de signal ou par nom.

### 🔍 Identification Profonde (DIS)
L'application tente de se connecter brièvement aux appareils pour extraire :
*   Le numéro de série.
*   Le nom du modèle.
*   La liste complète des services GATT (traduits en noms lisibles).

### ☁️ Synchronisation Firebase (Cloud)
*   **Stockage centralisé :** Toutes les détections sont envoyées sur un serveur distant.
*   **Filtres avancés :** Masquez les appareils "Unknown" ou ne gardez que ceux ayant des services identifiés.
*   **Consultation géographique :** Un bouton permet d'ouvrir instantanément la position d'un appareil dans votre application de navigation habituelle (Google Maps, Waze).

### 💾 Résilience Locale
*   **Base SQLite :** Enregistre une copie de chaque détection sur le téléphone, même sans connexion internet.

---

## 💡 Guide d'Utilisation

1.  **Démarrage :** Lancez l'application et accordez les permissions (Bluetooth et Localisation). Ces permissions sont indispensables pour détecter les appareils et enregistrer leur position.
2.  **Scan :** Laissez l'application ouverte. Le service de scan tourne en arrière-plan (une notification s'affiche) et continue de collecter des données même si vous changez d'application.
3.  **Analyse :** Cliquez sur "Appareils sur Firebase" pour consulter l'historique global. Utilisez le bouton "Voir sur la carte" pour localiser précisément un objet.
4.  **Nettoyage :** Utilisez l'icône de corbeille sur l'écran Firebase pour réinitialiser vos données de recensement.

---

## ⚠️ Notes Techniques
*   **Consommation batterie :** Le scan continu et le GPS sont énergivores. Il est conseillé d'utiliser l'application sur un appareil branché pour de longues périodes de recensement.
*   **Précision GPS :** Dépend de la qualité du capteur de votre téléphone et de la visibilité des satellites (plus précis en extérieur).
