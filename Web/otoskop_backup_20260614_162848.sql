-- MariaDB dump 10.19  Distrib 10.4.32-MariaDB, for Win64 (AMD64)
--
-- Host: 127.0.0.1    Database: otoskop
-- ------------------------------------------------------
-- Server version	10.4.32-MariaDB

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `otoskop`
--

/*!40000 DROP DATABASE IF EXISTS `otoskop`*/;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `otoskop` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */;

USE `otoskop`;

--
-- Table structure for table `media`
--

DROP TABLE IF EXISTS `media`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `media` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `type` enum('photo','video') NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `original_name` varchar(255) DEFAULT NULL,
  `mime` varchar(100) DEFAULT NULL,
  `file_size` bigint(20) DEFAULT 0,
  `target_name` varchar(120) DEFAULT NULL,
  `object_type` varchar(40) DEFAULT NULL,
  `azimuth` double DEFAULT NULL,
  `altitude` double DEFAULT NULL,
  `gps_lat` double DEFAULT NULL,
  `gps_lon` double DEFAULT NULL,
  `magnitude` double DEFAULT NULL,
  `ai_verified` tinyint(1) DEFAULT 0,
  `ai_confidence` double DEFAULT NULL,
  `ai_message` varchar(255) DEFAULT NULL,
  `width` int(11) DEFAULT NULL,
  `height` int(11) DEFAULT NULL,
  `fps` double DEFAULT NULL,
  `duration_sec` double DEFAULT NULL,
  `session_id` varchar(64) DEFAULT NULL,
  `notes` text DEFAULT NULL,
  `captured_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_type` (`type`),
  KEY `idx_target` (`target_name`),
  KEY `idx_captured` (`captured_at`),
  CONSTRAINT `fk_media_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=22 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `media`
--

LOCK TABLES `media` WRITE;
/*!40000 ALTER TABLE `media` DISABLE KEYS */;
INSERT INTO `media` VALUES (1,1,'photo','20260530_193520_382b3dc5667d.jpg','20260530_193520.jpg','image/jpeg',2088816,'Ay','Uydu',118,12,40.575,30.0133,-12.4,1,0.96,'Ay net ve iyi pozlanmış; kraterler belirgin seçiliyor.',4032,3024,NULL,NULL,'OTO-20260530-1935','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 19:35:20','2026-06-14 03:07:02'),(3,1,'photo','20260530_203605_76f7c78691bc.jpg','20260530_203605.jpg','image/jpeg',1537101,'Ay','Uydu',132,22,40.575,30.0133,-12.5,1,0.76,'Ay yüzeyi seçiliyor, hafif yumuşak ama kabul edilebilir.',4032,3024,NULL,NULL,'OTO-20260530-2036','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:36:05','2026-06-14 03:07:02'),(4,1,'photo','20260530_203608_96750e156b06.jpg','20260530_203608.jpg','image/jpeg',1513711,'Ay','Uydu',132.4,22.3,40.575,30.0133,-12.5,1,0.93,'Net dolunay, detaylar belirgin.',4032,3024,NULL,NULL,'OTO-20260530-2036','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:36:08','2026-06-14 03:07:02'),(5,1,'photo','20260530_203611_b807bbf6100c.jpg','20260530_203611.jpg','image/jpeg',1461993,'Ay','Uydu',132.8,22.6,40.575,30.0133,-12.5,0,0.48,'Odak kaçmış, görüntü bulanık.',4032,3024,NULL,NULL,'OTO-20260530-2036','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:36:11','2026-06-14 03:07:02'),(6,1,'photo','20260530_203612_70f1fe65ff79.jpg','20260530_203612.jpg','image/jpeg',1661432,'Ay','Uydu',133,22.7,40.575,30.0133,-12.5,0,0.5,'Bulanık, netlik yetersiz.',4032,3024,NULL,NULL,'OTO-20260530-2036','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:36:12','2026-06-14 03:07:02'),(7,1,'photo','20260530_203614_c8d91ac376d3.jpg','20260530_203614.jpg','image/jpeg',1565392,'Ay','Uydu',133.2,22.9,40.575,30.0133,-12.5,1,0.92,'Net ve dengeli pozlama.',4032,3024,NULL,NULL,'OTO-20260530-2036','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:36:14','2026-06-14 03:07:02'),(8,1,'photo','20260530_204001_696d62ccf0b6.jpg','20260530_204001.jpg','image/jpeg',1726310,'Ay','Uydu',135,24,40.575,30.0133,-12.5,0,0.55,'Aşırı pozlama, yüzey detayları kayıp.',4032,3024,NULL,NULL,'OTO-20260530-2036','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:40:01','2026-06-14 03:07:02'),(9,1,'photo','20260530_204003_93b6b6dc3662.jpg','20260530_204003.jpg','image/jpeg',2180092,'Ay','Uydu',135.2,24.1,40.575,30.0133,-12.5,0,0.28,'Tamamen odak dışı, cisim tanımlanamıyor.',4032,3024,NULL,NULL,'OTO-20260530-2036','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:40:03','2026-06-14 03:07:02'),(10,1,'photo','20260530_204005_c9964e686e12.jpg','20260530_204005.jpg','image/jpeg',1532219,'Ay','Uydu',135.4,24.2,40.575,30.0133,-12.5,0,0.61,'Parlak, orta detay; hafif aşırı pozlama.',4032,3024,NULL,NULL,'OTO-20260530-2036','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:40:05','2026-06-14 03:07:02'),(11,1,'photo','20260530_204006_82ee96442df3.jpg','20260530_204006.jpg','image/jpeg',1870067,'Ay','Uydu',135.5,24.3,40.575,30.0133,-12.5,0,0.5,'Kısmi kadraj, yumuşak ve loş.',4032,3024,NULL,NULL,'OTO-20260530-2036','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:40:06','2026-06-14 03:07:02'),(12,1,'photo','20260530_204015_2da4835a3f7c.jpg','20260530_204015.jpg','image/jpeg',1929187,'Ay','Uydu',135.8,24.5,40.575,30.0133,-12.5,1,0.8,'Detaylar görünür, hafif parlak ama kabul edilebilir.',4032,3024,NULL,NULL,'OTO-20260530-2036','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:40:15','2026-06-14 03:07:02'),(13,1,'photo','20260530_205258_436018581818.jpg','20260530_205258.jpg','image/jpeg',1546255,'Ay','Uydu',139,26.5,40.575,30.0133,-12.6,1,0.82,'Ay yüzeyi seçiliyor, hafif sıcak ton.',4032,3024,NULL,NULL,'OTO-20260530-2052','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:52:58','2026-06-14 03:07:02'),(14,1,'photo','20260530_205259_7b239bf811d6.jpg','20260530_205259.jpg','image/jpeg',1637261,'Ay','Uydu',139.1,26.6,40.575,30.0133,-12.6,1,0.88,'Net, iyi detay.',4032,3024,NULL,NULL,'OTO-20260530-2052','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:52:59','2026-06-14 03:07:02'),(15,1,'photo','20260530_205302_538c6ff7b594.jpg','20260530_205302.jpg','image/jpeg',2202993,'Ay','Uydu',139.3,26.7,40.575,30.0133,-12.6,0,0.38,'Yarım kadraj, bulanık.',4032,3024,NULL,NULL,'OTO-20260530-2052','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:53:02','2026-06-14 03:07:02'),(16,1,'photo','20260530_205305_a3ff17c6ac64.jpg','20260530_205305.jpg','image/jpeg',1766193,'Ay','Uydu',139.5,26.8,40.575,30.0133,-12.6,0,0.57,'Merkez aşırı pozlanmış, yumuşak.',4032,3024,NULL,NULL,'OTO-20260530-2052','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 20:53:05','2026-06-14 03:07:02'),(17,1,'photo','20260530_211748_edf5ebbe4895.jpg','20260530_211748.jpg','image/jpeg',1656315,'Ay','Uydu',146,30.5,40.575,30.0133,-12.6,1,0.9,'Net dolunay, kraterler belirgin.',4032,3024,NULL,NULL,'OTO-20260530-2117','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 21:17:48','2026-06-14 03:07:02'),(18,1,'photo','20260530_211749_a3cb2ab1a4e7.jpg','20260530_211749.jpg','image/jpeg',1573528,'Ay','Uydu',146.1,30.6,40.575,30.0133,-12.6,1,0.85,'İyi kadraj, hafif yumuşak.',4032,3024,NULL,NULL,'OTO-20260530-2117','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 21:17:49','2026-06-14 03:07:02'),(19,1,'photo','20260530_211749_929af1f595c3.png','20260530_211749.png','image/png',1139574,'Mars','Gezegen',285,22,40.575,30.0133,1,1,0.9,'Mars diski ve yüzey albedo desenleri seçiliyor.',1086,1448,NULL,NULL,'OTO-20260530-2117','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 21:17:49','2026-06-14 03:07:02'),(20,1,'photo','20260530_211753_28e05fbcfa5e.jpg','20260530_211753.jpg','image/jpeg',1844787,'Ay','Uydu',146.4,30.8,40.575,30.0133,-12.6,0,0.6,'Alt kısımda parlama/sis; orta kalite.',4032,3024,NULL,NULL,'OTO-20260530-2117','Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından).','2026-05-30 21:17:53','2026-06-14 03:07:02');
/*!40000 ALTER TABLE `media` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `users` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `email` varchar(190) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `role` enum('user','admin') NOT NULL DEFAULT 'user',
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`),
  UNIQUE KEY `email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `users`
--

LOCK TABLES `users` WRITE;
/*!40000 ALTER TABLE `users` DISABLE KEYS */;
INSERT INTO `users` VALUES (1,'admin','admin@admin.admin','$2y$10$r7Wzsvv/GElNziE6c2vKxuix4TgAQr1UAqq5jpTA/ha3VZY.MCuX6','admin','2026-06-10 12:02:01');
/*!40000 ALTER TABLE `users` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping events for database 'otoskop'
--

--
-- Dumping routines for database 'otoskop'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-06-14 16:28:48
