CREATE DATABASE  IF NOT EXISTS `zirowms_v1` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;
USE `zirowms_v1`;
-- MySQL dump 10.13  Distrib 8.0.44, for Win64 (x86_64)
--
-- Host: localhost    Database: zirowms_v1
-- ------------------------------------------------------
-- Server version	8.0.44

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `business_partner_addresses`
--

DROP TABLE IF EXISTS `business_partner_addresses`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `business_partner_addresses` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `business_partner_id` bigint NOT NULL,
  `address_type` varchar(30) NOT NULL DEFAULT 'GENERAL',
  `label` varchar(100) DEFAULT NULL,
  `recipient_name` varchar(200) DEFAULT NULL,
  `phone` varchar(50) DEFAULT NULL,
  `address_line_1` varchar(255) NOT NULL,
  `address_line_2` varchar(255) DEFAULT NULL,
  `district` varchar(120) DEFAULT NULL,
  `city` varchar(120) DEFAULT NULL,
  `province` varchar(120) DEFAULT NULL,
  `postal_code` varchar(20) DEFAULT NULL,
  `country_code` char(2) NOT NULL DEFAULT 'ID',
  `latitude` decimal(10,7) DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `is_default` tinyint(1) NOT NULL DEFAULT '0',
  `notes` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_business_partner_addresses_public_id` (`public_id`),
  UNIQUE KEY `uk_business_partner_addresses_id_tenant_partner` (`id`,`tenant_id`,`business_partner_id`),
  KEY `idx_business_partner_addresses_partner` (`tenant_id`,`business_partner_id`),
  KEY `idx_business_partner_addresses_type` (`tenant_id`,`address_type`),
  KEY `fk_business_partner_addresses_partner` (`business_partner_id`,`tenant_id`),
  CONSTRAINT `fk_business_partner_addresses_partner` FOREIGN KEY (`business_partner_id`, `tenant_id`) REFERENCES `business_partners` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_business_partner_addresses_latitude` CHECK (((`latitude` is null) or (`latitude` between -(90) and 90))),
  CONSTRAINT `chk_business_partner_addresses_longitude` CHECK (((`longitude` is null) or (`longitude` between -(180) and 180))),
  CONSTRAINT `chk_business_partner_addresses_type` CHECK ((`address_type` in (_utf8mb4'GENERAL',_utf8mb4'BILLING',_utf8mb4'SHIPPING',_utf8mb4'WAREHOUSE',_utf8mb4'OFFICE',_utf8mb4'OTHER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `business_partner_contacts`
--

DROP TABLE IF EXISTS `business_partner_contacts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `business_partner_contacts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `business_partner_id` bigint NOT NULL,
  `contact_name` varchar(200) NOT NULL,
  `job_title` varchar(120) DEFAULT NULL,
  `department` varchar(120) DEFAULT NULL,
  `email` varchar(150) DEFAULT NULL,
  `phone` varchar(50) DEFAULT NULL,
  `mobile_phone` varchar(50) DEFAULT NULL,
  `is_primary` tinyint(1) NOT NULL DEFAULT '0',
  `notes` varchar(500) DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_business_partner_contacts_public_id` (`public_id`),
  KEY `idx_business_partner_contacts_partner` (`tenant_id`,`business_partner_id`),
  KEY `idx_business_partner_contacts_email` (`tenant_id`,`email`),
  KEY `fk_business_partner_contacts_partner` (`business_partner_id`,`tenant_id`),
  CONSTRAINT `fk_business_partner_contacts_partner` FOREIGN KEY (`business_partner_id`, `tenant_id`) REFERENCES `business_partners` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_business_partner_contacts_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `business_partner_roles`
--

DROP TABLE IF EXISTS `business_partner_roles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `business_partner_roles` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `business_partner_id` bigint NOT NULL,
  `role_type` varchar(30) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_business_partner_roles` (`tenant_id`,`business_partner_id`,`role_type`),
  KEY `idx_business_partner_roles_type` (`tenant_id`,`role_type`),
  KEY `fk_business_partner_roles_partner` (`business_partner_id`,`tenant_id`),
  CONSTRAINT `fk_business_partner_roles_partner` FOREIGN KEY (`business_partner_id`, `tenant_id`) REFERENCES `business_partners` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_business_partner_roles_type` CHECK ((`role_type` in (_utf8mb4'SUPPLIER',_utf8mb4'CUSTOMER',_utf8mb4'CARRIER',_utf8mb4'SERVICE_PROVIDER',_utf8mb4'OTHER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `business_partners`
--

DROP TABLE IF EXISTS `business_partners`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `business_partners` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `partner_code` varchar(50) NOT NULL,
  `partner_name` varchar(200) NOT NULL,
  `legal_name` varchar(250) DEFAULT NULL,
  `tax_number` varchar(100) DEFAULT NULL,
  `email` varchar(150) DEFAULT NULL,
  `phone` varchar(50) DEFAULT NULL,
  `website` varchar(255) DEFAULT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_business_partners_public_id` (`public_id`),
  UNIQUE KEY `uk_business_partners_tenant_code` (`tenant_id`,`partner_code`),
  UNIQUE KEY `uk_business_partners_id_tenant` (`id`,`tenant_id`),
  KEY `idx_business_partners_name` (`tenant_id`,`partner_name`),
  KEY `idx_business_partners_status` (`tenant_id`,`status`),
  CONSTRAINT `fk_business_partners_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_business_partners_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE',_utf8mb4'BLOCKED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inbound_receipt_line_lots`
--

DROP TABLE IF EXISTS `inbound_receipt_line_lots`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_receipt_line_lots` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `inbound_receipt_line_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `lot_id` bigint NOT NULL,
  `accepted_qty` decimal(19,4) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_receipt_line_lots` (`tenant_id`,`inbound_receipt_line_id`,`lot_id`),
  KEY `idx_inbound_receipt_line_lots_lot` (`tenant_id`,`lot_id`),
  KEY `fk_inbound_receipt_line_lots_line` (`inbound_receipt_line_id`,`tenant_id`,`item_id`),
  KEY `fk_inbound_receipt_line_lots_lot` (`lot_id`,`tenant_id`,`item_id`),
  CONSTRAINT `fk_inbound_receipt_line_lots_line` FOREIGN KEY (`inbound_receipt_line_id`, `tenant_id`, `item_id`) REFERENCES `inbound_receipt_lines` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inbound_receipt_line_lots_lot` FOREIGN KEY (`lot_id`, `tenant_id`, `item_id`) REFERENCES `inventory_lots` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inbound_receipt_line_lots_qty` CHECK ((`accepted_qty` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inbound_receipt_line_serials`
--

DROP TABLE IF EXISTS `inbound_receipt_line_serials`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_receipt_line_serials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `inbound_receipt_line_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `serial_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_receipt_line_serials_serial` (`tenant_id`,`serial_id`),
  UNIQUE KEY `uk_inbound_receipt_line_serials_line` (`tenant_id`,`inbound_receipt_line_id`,`serial_id`),
  KEY `fk_inbound_receipt_line_serials_line` (`inbound_receipt_line_id`,`tenant_id`,`item_id`),
  KEY `fk_inbound_receipt_line_serials_serial` (`serial_id`,`tenant_id`,`item_id`),
  CONSTRAINT `fk_inbound_receipt_line_serials_line` FOREIGN KEY (`inbound_receipt_line_id`, `tenant_id`, `item_id`) REFERENCES `inbound_receipt_lines` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inbound_receipt_line_serials_serial` FOREIGN KEY (`serial_id`, `tenant_id`, `item_id`) REFERENCES `inventory_serials` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inbound_receipt_lines`
--

DROP TABLE IF EXISTS `inbound_receipt_lines`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_receipt_lines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `inbound_receipt_id` bigint NOT NULL,
  `line_number` int NOT NULL,
  `item_id` bigint NOT NULL,
  `uom_id` bigint NOT NULL,
  `expected_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `received_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `accepted_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `rejected_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `rejection_reason` varchar(500) DEFAULT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_receipt_lines_public_id` (`public_id`),
  UNIQUE KEY `uk_inbound_receipt_lines_number` (`tenant_id`,`inbound_receipt_id`,`line_number`),
  UNIQUE KEY `uk_inbound_receipt_lines_id_tenant_item` (`id`,`tenant_id`,`item_id`),
  UNIQUE KEY `uk_inbound_receipt_lines_id_tenant_receipt_item` (`id`,`tenant_id`,`inbound_receipt_id`,`item_id`),
  KEY `idx_inbound_receipt_lines_item` (`tenant_id`,`item_id`),
  KEY `fk_inbound_receipt_lines_header` (`inbound_receipt_id`,`tenant_id`),
  KEY `fk_inbound_receipt_lines_item` (`item_id`,`tenant_id`),
  KEY `fk_inbound_receipt_lines_uom` (`uom_id`),
  CONSTRAINT `fk_inbound_receipt_lines_header` FOREIGN KEY (`inbound_receipt_id`, `tenant_id`) REFERENCES `inbound_receipts` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inbound_receipt_lines_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inbound_receipt_lines_uom` FOREIGN KEY (`uom_id`) REFERENCES `units_of_measure` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inbound_receipt_lines_number` CHECK ((`line_number` > 0)),
  CONSTRAINT `chk_inbound_receipt_lines_quantities` CHECK (((`expected_qty` >= 0) and (`received_qty` >= 0) and (`accepted_qty` >= 0) and (`rejected_qty` >= 0) and (`received_qty` = (`accepted_qty` + `rejected_qty`)))),
  CONSTRAINT `chk_inbound_receipt_lines_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'RECEIVING',_utf8mb4'COMPLETED',_utf8mb4'REJECTED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inbound_receipts`
--

DROP TABLE IF EXISTS `inbound_receipts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_receipts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `supplier_partner_id` bigint NOT NULL,
  `supplier_address_id` bigint DEFAULT NULL,
  `document_number` varchar(60) NOT NULL,
  `external_reference` varchar(100) DEFAULT NULL,
  `receipt_type` varchar(30) NOT NULL DEFAULT 'PURCHASE',
  `status` varchar(30) NOT NULL DEFAULT 'DRAFT',
  `expected_at` datetime(6) DEFAULT NULL,
  `received_at` datetime(6) DEFAULT NULL,
  `posted_at` datetime(6) DEFAULT NULL,
  `receiving_location_id` bigint NOT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `received_by_membership_id` bigint DEFAULT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_receipts_public_id` (`public_id`),
  UNIQUE KEY `uk_inbound_receipts_tenant_document` (`tenant_id`,`document_number`),
  UNIQUE KEY `uk_inbound_receipts_id_tenant` (`id`,`tenant_id`),
  UNIQUE KEY `uk_inbound_receipts_id_tenant_warehouse` (`id`,`tenant_id`,`warehouse_id`),
  KEY `idx_inbound_receipts_supplier` (`tenant_id`,`supplier_partner_id`),
  KEY `idx_inbound_receipts_warehouse_status` (`tenant_id`,`warehouse_id`,`status`),
  KEY `idx_inbound_receipts_received_at` (`tenant_id`,`received_at`),
  KEY `fk_inbound_receipts_warehouse` (`warehouse_id`,`tenant_id`),
  KEY `fk_inbound_receipts_supplier` (`supplier_partner_id`,`tenant_id`),
  KEY `fk_inbound_receipts_supplier_address` (`supplier_address_id`,`tenant_id`,`supplier_partner_id`),
  KEY `fk_inbound_receipts_location` (`receiving_location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_inbound_receipts_created_by` (`created_by_membership_id`,`tenant_id`),
  KEY `fk_inbound_receipts_received_by` (`received_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_inbound_receipts_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inbound_receipts_location` FOREIGN KEY (`receiving_location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inbound_receipts_received_by` FOREIGN KEY (`received_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inbound_receipts_supplier` FOREIGN KEY (`supplier_partner_id`, `tenant_id`) REFERENCES `business_partners` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inbound_receipts_supplier_address` FOREIGN KEY (`supplier_address_id`, `tenant_id`, `supplier_partner_id`) REFERENCES `business_partner_addresses` (`id`, `tenant_id`, `business_partner_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inbound_receipts_warehouse` FOREIGN KEY (`warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inbound_receipts_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'IN_PROGRESS',_utf8mb4'COMPLETED',_utf8mb4'POSTED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_inbound_receipts_type` CHECK ((`receipt_type` in (_utf8mb4'PURCHASE',_utf8mb4'RETURN',_utf8mb4'TRANSFER_IN',_utf8mb4'OTHER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inventory_adjustment_allocations`
--

DROP TABLE IF EXISTS `inventory_adjustment_allocations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_adjustment_allocations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `inventory_adjustment_line_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `location_id` bigint NOT NULL,
  `lot_id` bigint DEFAULT NULL,
  `lot_key` bigint GENERATED ALWAYS AS (coalesce(`lot_id`,0)) STORED,
  `stock_status` varchar(30) NOT NULL DEFAULT 'AVAILABLE',
  `system_qty` decimal(19,4) NOT NULL,
  `counted_qty` decimal(19,4) NOT NULL,
  `adjustment_qty` decimal(19,4) NOT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_adjustment_allocations_id_tenant_item` (`id`,`tenant_id`,`item_id`),
  UNIQUE KEY `uk_inventory_adjustment_allocations_bucket` (`tenant_id`,`inventory_adjustment_line_id`,`location_id`,`lot_key`,`stock_status`),
  KEY `idx_inventory_adjustment_allocations_location` (`tenant_id`,`warehouse_id`,`location_id`),
  KEY `idx_inventory_adjustment_allocations_lot` (`tenant_id`,`lot_id`),
  KEY `fk_inventory_adjustment_allocations_line` (`inventory_adjustment_line_id`,`tenant_id`,`item_id`,`warehouse_id`),
  KEY `fk_inventory_adjustment_allocations_location` (`location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_inventory_adjustment_allocations_lot` (`lot_id`,`tenant_id`,`item_id`),
  CONSTRAINT `fk_inventory_adjustment_allocations_line` FOREIGN KEY (`inventory_adjustment_line_id`, `tenant_id`, `item_id`, `warehouse_id`) REFERENCES `inventory_adjustment_lines` (`id`, `tenant_id`, `item_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_adjustment_allocations_location` FOREIGN KEY (`location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_adjustment_allocations_lot` FOREIGN KEY (`lot_id`, `tenant_id`, `item_id`) REFERENCES `inventory_lots` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inventory_adjustment_allocations_qty` CHECK (((`system_qty` >= 0) and (`counted_qty` >= 0) and (`adjustment_qty` = (`counted_qty` - `system_qty`)))),
  CONSTRAINT `chk_inventory_adjustment_allocations_status` CHECK ((`stock_status` in (_utf8mb4'AVAILABLE',_utf8mb4'QUARANTINED',_utf8mb4'DAMAGED',_utf8mb4'EXPIRED',_utf8mb4'BLOCKED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inventory_adjustment_line_serials`
--

DROP TABLE IF EXISTS `inventory_adjustment_line_serials`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_adjustment_line_serials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `inventory_adjustment_allocation_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `serial_id` bigint NOT NULL,
  `observation_status` varchar(20) NOT NULL,
  `notes` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_adjustment_line_serials_allocation` (`tenant_id`,`inventory_adjustment_allocation_id`,`serial_id`),
  KEY `idx_inventory_adjustment_line_serials_serial` (`tenant_id`,`serial_id`),
  KEY `fk_inventory_adjustment_line_serials_allocation` (`inventory_adjustment_allocation_id`,`tenant_id`,`item_id`),
  KEY `fk_inventory_adjustment_line_serials_serial` (`serial_id`,`tenant_id`,`item_id`),
  CONSTRAINT `fk_inventory_adjustment_line_serials_allocation` FOREIGN KEY (`inventory_adjustment_allocation_id`, `tenant_id`, `item_id`) REFERENCES `inventory_adjustment_allocations` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_adjustment_line_serials_serial` FOREIGN KEY (`serial_id`, `tenant_id`, `item_id`) REFERENCES `inventory_serials` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inventory_adjustment_line_serials_observation` CHECK ((`observation_status` in (_utf8mb4'PRESENT',_utf8mb4'MISSING')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inventory_adjustment_lines`
--

DROP TABLE IF EXISTS `inventory_adjustment_lines`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_adjustment_lines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `inventory_adjustment_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `line_number` int NOT NULL,
  `item_id` bigint NOT NULL,
  `uom_id` bigint NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_adjustment_lines_public_id` (`public_id`),
  UNIQUE KEY `uk_inventory_adjustment_lines_number` (`tenant_id`,`inventory_adjustment_id`,`line_number`),
  UNIQUE KEY `uk_inventory_adjustment_lines_id_tenant_item` (`id`,`tenant_id`,`item_id`),
  UNIQUE KEY `uk_inventory_adjustment_lines_scope` (`id`,`tenant_id`,`item_id`,`warehouse_id`),
  KEY `idx_inventory_adjustment_lines_item` (`tenant_id`,`item_id`),
  KEY `fk_inventory_adjustment_lines_header` (`inventory_adjustment_id`,`tenant_id`,`warehouse_id`),
  KEY `fk_inventory_adjustment_lines_item` (`item_id`,`tenant_id`),
  KEY `fk_inventory_adjustment_lines_uom` (`uom_id`),
  CONSTRAINT `fk_inventory_adjustment_lines_header` FOREIGN KEY (`inventory_adjustment_id`, `tenant_id`, `warehouse_id`) REFERENCES `inventory_adjustments` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_adjustment_lines_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_adjustment_lines_uom` FOREIGN KEY (`uom_id`) REFERENCES `units_of_measure` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inventory_adjustment_lines_number` CHECK ((`line_number` > 0)),
  CONSTRAINT `chk_inventory_adjustment_lines_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'COUNTED',_utf8mb4'REVIEWED',_utf8mb4'ADJUSTED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inventory_adjustments`
--

DROP TABLE IF EXISTS `inventory_adjustments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_adjustments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `document_number` varchar(60) NOT NULL,
  `external_reference` varchar(100) DEFAULT NULL,
  `adjustment_type` varchar(30) NOT NULL DEFAULT 'CORRECTION',
  `status` varchar(30) NOT NULL DEFAULT 'DRAFT',
  `reason_code` varchar(50) NOT NULL,
  `reason_description` varchar(500) DEFAULT NULL,
  `requested_at` datetime(6) DEFAULT NULL,
  `submitted_at` datetime(6) DEFAULT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `rejected_at` datetime(6) DEFAULT NULL,
  `posted_at` datetime(6) DEFAULT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `approved_by_membership_id` bigint DEFAULT NULL,
  `rejected_by_membership_id` bigint DEFAULT NULL,
  `posted_by_membership_id` bigint DEFAULT NULL,
  `approval_notes` varchar(1000) DEFAULT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_adjustments_public_id` (`public_id`),
  UNIQUE KEY `uk_inventory_adjustments_tenant_document` (`tenant_id`,`document_number`),
  UNIQUE KEY `uk_inventory_adjustments_id_tenant_warehouse` (`id`,`tenant_id`,`warehouse_id`),
  KEY `idx_inventory_adjustments_warehouse_status` (`tenant_id`,`warehouse_id`,`status`),
  KEY `idx_inventory_adjustments_type` (`tenant_id`,`adjustment_type`),
  KEY `idx_inventory_adjustments_created` (`tenant_id`,`created_at`),
  KEY `fk_inventory_adjustments_warehouse` (`warehouse_id`,`tenant_id`),
  KEY `fk_inventory_adjustments_created_by` (`created_by_membership_id`,`tenant_id`),
  KEY `fk_inventory_adjustments_approved_by` (`approved_by_membership_id`,`tenant_id`),
  KEY `fk_inventory_adjustments_rejected_by` (`rejected_by_membership_id`,`tenant_id`),
  KEY `fk_inventory_adjustments_posted_by` (`posted_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_inventory_adjustments_approved_by` FOREIGN KEY (`approved_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_adjustments_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_adjustments_posted_by` FOREIGN KEY (`posted_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_adjustments_rejected_by` FOREIGN KEY (`rejected_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_adjustments_warehouse` FOREIGN KEY (`warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inventory_adjustments_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'SUBMITTED',_utf8mb4'APPROVED',_utf8mb4'REJECTED',_utf8mb4'POSTED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_inventory_adjustments_type` CHECK ((`adjustment_type` in (_utf8mb4'PHYSICAL_COUNT',_utf8mb4'DAMAGE',_utf8mb4'LOSS',_utf8mb4'FOUND',_utf8mb4'EXPIRY',_utf8mb4'CORRECTION',_utf8mb4'OTHER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inventory_balances`
--

DROP TABLE IF EXISTS `inventory_balances`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_balances` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `location_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `lot_id` bigint DEFAULT NULL,
  `stock_status` varchar(30) NOT NULL DEFAULT 'AVAILABLE',
  `on_hand_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `reserved_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `available_qty` decimal(19,4) GENERATED ALWAYS AS ((`on_hand_qty` - `reserved_qty`)) STORED,
  `lot_key` bigint GENERATED ALWAYS AS (coalesce(`lot_id`,0)) STORED,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_balances_public_id` (`public_id`),
  UNIQUE KEY `uk_inventory_balances_bucket` (`tenant_id`,`warehouse_id`,`location_id`,`item_id`,`lot_key`,`stock_status`),
  KEY `idx_inventory_balances_item` (`tenant_id`,`item_id`,`warehouse_id`),
  KEY `idx_inventory_balances_location` (`tenant_id`,`warehouse_id`,`location_id`),
  KEY `idx_inventory_balances_lot` (`tenant_id`,`lot_id`),
  KEY `idx_inventory_balances_status` (`tenant_id`,`stock_status`),
  KEY `fk_inventory_balances_item` (`item_id`,`tenant_id`),
  KEY `fk_inventory_balances_location` (`location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_inventory_balances_lot` (`lot_id`,`tenant_id`,`item_id`),
  CONSTRAINT `fk_inventory_balances_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_balances_location` FOREIGN KEY (`location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_balances_lot` FOREIGN KEY (`lot_id`, `tenant_id`, `item_id`) REFERENCES `inventory_lots` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inventory_balances_on_hand` CHECK ((`on_hand_qty` >= 0)),
  CONSTRAINT `chk_inventory_balances_reserved` CHECK (((`reserved_qty` >= 0) and (`reserved_qty` <= `on_hand_qty`))),
  CONSTRAINT `chk_inventory_balances_reserved_status` CHECK (((`stock_status` = _utf8mb4'AVAILABLE') or (`reserved_qty` = 0))),
  CONSTRAINT `chk_inventory_balances_status` CHECK ((`stock_status` in (_utf8mb4'AVAILABLE',_utf8mb4'QUARANTINED',_utf8mb4'DAMAGED',_utf8mb4'EXPIRED',_utf8mb4'BLOCKED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inventory_ledger`
--

DROP TABLE IF EXISTS `inventory_ledger`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `movement_group_id` char(36) NOT NULL,
  `request_id` char(36) NOT NULL,
  `entry_no` int NOT NULL,
  `movement_type` varchar(40) NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `location_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `lot_id` bigint DEFAULT NULL,
  `serial_id` bigint DEFAULT NULL,
  `stock_status` varchar(30) NOT NULL,
  `quantity_delta` decimal(19,4) NOT NULL,
  `balance_before_qty` decimal(19,4) NOT NULL,
  `balance_after_qty` decimal(19,4) NOT NULL,
  `reference_type` varchar(50) DEFAULT NULL,
  `reference_id` bigint DEFAULT NULL,
  `reference_line_id` bigint DEFAULT NULL,
  `actor_membership_id` bigint DEFAULT NULL,
  `occurred_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_ledger_public_id` (`public_id`),
  UNIQUE KEY `uk_inventory_ledger_request_entry` (`tenant_id`,`request_id`,`entry_no`),
  KEY `idx_inventory_ledger_movement_group` (`tenant_id`,`movement_group_id`),
  KEY `idx_inventory_ledger_item_time` (`tenant_id`,`item_id`,`occurred_at`),
  KEY `idx_inventory_ledger_location_time` (`tenant_id`,`warehouse_id`,`location_id`,`occurred_at`),
  KEY `idx_inventory_ledger_lot` (`tenant_id`,`lot_id`,`occurred_at`),
  KEY `idx_inventory_ledger_serial` (`tenant_id`,`serial_id`),
  KEY `idx_inventory_ledger_reference` (`tenant_id`,`reference_type`,`reference_id`),
  KEY `idx_inventory_ledger_actor` (`tenant_id`,`actor_membership_id`),
  KEY `fk_inventory_ledger_item` (`item_id`,`tenant_id`),
  KEY `fk_inventory_ledger_location` (`location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_inventory_ledger_lot` (`lot_id`,`tenant_id`,`item_id`),
  KEY `fk_inventory_ledger_serial` (`serial_id`,`tenant_id`,`item_id`),
  KEY `fk_inventory_ledger_actor` (`actor_membership_id`,`tenant_id`),
  CONSTRAINT `fk_inventory_ledger_actor` FOREIGN KEY (`actor_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_ledger_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_ledger_location` FOREIGN KEY (`location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_ledger_lot` FOREIGN KEY (`lot_id`, `tenant_id`, `item_id`) REFERENCES `inventory_lots` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_ledger_serial` FOREIGN KEY (`serial_id`, `tenant_id`, `item_id`) REFERENCES `inventory_serials` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inventory_ledger_balance_after` CHECK ((`balance_after_qty` >= 0)),
  CONSTRAINT `chk_inventory_ledger_balance_before` CHECK ((`balance_before_qty` >= 0)),
  CONSTRAINT `chk_inventory_ledger_balance_calculation` CHECK ((`balance_after_qty` = (`balance_before_qty` + `quantity_delta`))),
  CONSTRAINT `chk_inventory_ledger_movement_type` CHECK ((`movement_type` in (_utf8mb4'RECEIPT',_utf8mb4'ISSUE',_utf8mb4'TRANSFER',_utf8mb4'ADJUSTMENT_IN',_utf8mb4'ADJUSTMENT_OUT',_utf8mb4'RETURN',_utf8mb4'DAMAGE',_utf8mb4'STATUS_CHANGE',_utf8mb4'OPENING_BALANCE'))),
  CONSTRAINT `chk_inventory_ledger_quantity` CHECK ((`quantity_delta` <> 0)),
  CONSTRAINT `chk_inventory_ledger_serial_quantity` CHECK (((`serial_id` is null) or (abs(`quantity_delta`) = 1.0000))),
  CONSTRAINT `chk_inventory_ledger_stock_status` CHECK ((`stock_status` in (_utf8mb4'AVAILABLE',_utf8mb4'QUARANTINED',_utf8mb4'DAMAGED',_utf8mb4'EXPIRED',_utf8mb4'BLOCKED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inventory_lots`
--

DROP TABLE IF EXISTS `inventory_lots`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_lots` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `lot_number` varchar(120) NOT NULL,
  `supplier_lot_number` varchar(120) DEFAULT NULL,
  `manufacture_date` date DEFAULT NULL,
  `received_date` date DEFAULT NULL,
  `expiry_date` date DEFAULT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'ACTIVE',
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_lots_public_id` (`public_id`),
  UNIQUE KEY `uk_inventory_lots_item_number` (`tenant_id`,`item_id`,`lot_number`),
  UNIQUE KEY `uk_inventory_lots_id_tenant_item` (`id`,`tenant_id`,`item_id`),
  KEY `idx_inventory_lots_expiry` (`tenant_id`,`expiry_date`,`status`),
  KEY `idx_inventory_lots_item_status` (`tenant_id`,`item_id`,`status`),
  KEY `fk_inventory_lots_item` (`item_id`,`tenant_id`),
  CONSTRAINT `fk_inventory_lots_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inventory_lots_dates` CHECK (((`manufacture_date` is null) or (`expiry_date` is null) or (`expiry_date` >= `manufacture_date`))),
  CONSTRAINT `chk_inventory_lots_received_date` CHECK (((`received_date` is null) or (`manufacture_date` is null) or (`received_date` >= `manufacture_date`))),
  CONSTRAINT `chk_inventory_lots_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'QUARANTINED',_utf8mb4'BLOCKED',_utf8mb4'EXPIRED',_utf8mb4'CLOSED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `inventory_serials`
--

DROP TABLE IF EXISTS `inventory_serials`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_serials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `lot_id` bigint DEFAULT NULL,
  `current_warehouse_id` bigint DEFAULT NULL,
  `current_location_id` bigint DEFAULT NULL,
  `serial_number` varchar(150) NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'AVAILABLE',
  `received_at` datetime(6) DEFAULT NULL,
  `shipped_at` datetime(6) DEFAULT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_serials_public_id` (`public_id`),
  UNIQUE KEY `uk_inventory_serials_tenant_number` (`tenant_id`,`serial_number`),
  UNIQUE KEY `uk_inventory_serials_id_tenant_item` (`id`,`tenant_id`,`item_id`),
  KEY `idx_inventory_serials_item_status` (`tenant_id`,`item_id`,`status`),
  KEY `idx_inventory_serials_lot` (`lot_id`,`tenant_id`,`item_id`),
  KEY `idx_inventory_serials_location` (`current_warehouse_id`,`current_location_id`,`tenant_id`),
  KEY `fk_inventory_serials_item` (`item_id`,`tenant_id`),
  KEY `fk_inventory_serials_location` (`current_location_id`,`current_warehouse_id`,`tenant_id`),
  CONSTRAINT `fk_inventory_serials_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_serials_location` FOREIGN KEY (`current_location_id`, `current_warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inventory_serials_lot` FOREIGN KEY (`lot_id`, `tenant_id`, `item_id`) REFERENCES `inventory_lots` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_inventory_serials_location_pair` CHECK ((((`current_warehouse_id` is null) and (`current_location_id` is null)) or ((`current_warehouse_id` is not null) and (`current_location_id` is not null)))),
  CONSTRAINT `chk_inventory_serials_shipped_at` CHECK (((`status` <> _utf8mb4'SHIPPED') or (`shipped_at` is not null))),
  CONSTRAINT `chk_inventory_serials_status` CHECK ((`status` in (_utf8mb4'AVAILABLE',_utf8mb4'RESERVED',_utf8mb4'PICKED',_utf8mb4'SHIPPED',_utf8mb4'DAMAGED',_utf8mb4'QUARANTINED',_utf8mb4'RETURNED',_utf8mb4'LOST')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `item_categories`
--

DROP TABLE IF EXISTS `item_categories`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `item_categories` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `parent_category_id` bigint DEFAULT NULL,
  `category_code` varchar(80) NOT NULL,
  `category_name` varchar(150) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_categories_public_id` (`public_id`),
  UNIQUE KEY `uk_categories_tenant_code` (`tenant_id`,`category_code`),
  UNIQUE KEY `uk_categories_id_tenant` (`id`,`tenant_id`),
  KEY `idx_categories_parent` (`parent_category_id`,`tenant_id`),
  KEY `idx_categories_status` (`tenant_id`,`status`),
  CONSTRAINT `fk_categories_parent` FOREIGN KEY (`parent_category_id`, `tenant_id`) REFERENCES `item_categories` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_categories_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_categories_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `item_identifiers`
--

DROP TABLE IF EXISTS `item_identifiers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `item_identifiers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `identifier_type` varchar(30) NOT NULL,
  `identifier_value` varchar(150) NOT NULL,
  `is_primary` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item_identifiers_public_id` (`public_id`),
  UNIQUE KEY `uk_item_identifier_value` (`tenant_id`,`identifier_type`,`identifier_value`),
  KEY `idx_item_identifiers_item` (`item_id`,`tenant_id`),
  CONSTRAINT `fk_item_identifiers_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_item_identifier_type` CHECK ((`identifier_type` in (_utf8mb4'BARCODE',_utf8mb4'QR_CODE',_utf8mb4'GTIN',_utf8mb4'ALTERNATE_SKU')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `item_uom_conversions`
--

DROP TABLE IF EXISTS `item_uom_conversions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `item_uom_conversions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `from_uom_id` bigint NOT NULL,
  `to_uom_id` bigint NOT NULL,
  `conversion_factor` decimal(19,8) NOT NULL,
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item_uom_conversion` (`item_id`,`from_uom_id`,`to_uom_id`),
  KEY `idx_item_uom_conversion_tenant` (`tenant_id`,`item_id`),
  KEY `fk_item_uom_conversion_item` (`item_id`,`tenant_id`),
  KEY `fk_item_uom_conversion_from` (`from_uom_id`),
  KEY `fk_item_uom_conversion_to` (`to_uom_id`),
  CONSTRAINT `fk_item_uom_conversion_from` FOREIGN KEY (`from_uom_id`) REFERENCES `units_of_measure` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_item_uom_conversion_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_item_uom_conversion_to` FOREIGN KEY (`to_uom_id`) REFERENCES `units_of_measure` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_item_uom_conversion_different` CHECK ((`from_uom_id` <> `to_uom_id`)),
  CONSTRAINT `chk_item_uom_conversion_factor` CHECK ((`conversion_factor` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `items`
--

DROP TABLE IF EXISTS `items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `category_id` bigint DEFAULT NULL,
  `sku` varchar(100) NOT NULL,
  `item_name` varchar(200) NOT NULL,
  `description` text,
  `base_uom_id` bigint NOT NULL,
  `weight_per_base_uom` decimal(19,6) DEFAULT NULL,
  `weight_uom_id` bigint DEFAULT NULL,
  `length` decimal(19,6) DEFAULT NULL,
  `width` decimal(19,6) DEFAULT NULL,
  `height` decimal(19,6) DEFAULT NULL,
  `dimension_uom_id` bigint DEFAULT NULL,
  `tracking_method` varchar(30) NOT NULL DEFAULT 'NONE',
  `is_stock_item` tinyint(1) NOT NULL DEFAULT '1',
  `is_purchasable` tinyint(1) NOT NULL DEFAULT '1',
  `is_sellable` tinyint(1) NOT NULL DEFAULT '1',
  `minimum_stock_level` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `status` varchar(30) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_items_public_id` (`public_id`),
  UNIQUE KEY `uk_items_tenant_sku` (`tenant_id`,`sku`),
  UNIQUE KEY `uk_items_id_tenant` (`id`,`tenant_id`),
  KEY `idx_items_category` (`category_id`,`tenant_id`),
  KEY `idx_items_tenant_status` (`tenant_id`,`status`),
  KEY `idx_items_tracking` (`tenant_id`,`tracking_method`),
  KEY `fk_items_base_uom` (`base_uom_id`),
  KEY `fk_items_weight_uom` (`weight_uom_id`),
  KEY `fk_items_dimension_uom` (`dimension_uom_id`),
  CONSTRAINT `fk_items_base_uom` FOREIGN KEY (`base_uom_id`) REFERENCES `units_of_measure` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_items_category` FOREIGN KEY (`category_id`, `tenant_id`) REFERENCES `item_categories` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_items_dimension_uom` FOREIGN KEY (`dimension_uom_id`) REFERENCES `units_of_measure` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_items_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_items_weight_uom` FOREIGN KEY (`weight_uom_id`) REFERENCES `units_of_measure` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_items_dimension_uom` CHECK ((((`length` is null) and (`width` is null) and (`height` is null) and (`dimension_uom_id` is null)) or ((`length` is not null) and (`width` is not null) and (`height` is not null) and (`dimension_uom_id` is not null)))),
  CONSTRAINT `chk_items_dimensions` CHECK ((((`length` is null) or (`length` >= 0)) and ((`width` is null) or (`width` >= 0)) and ((`height` is null) or (`height` >= 0)))),
  CONSTRAINT `chk_items_minimum_stock` CHECK ((`minimum_stock_level` >= 0)),
  CONSTRAINT `chk_items_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE',_utf8mb4'DISCONTINUED'))),
  CONSTRAINT `chk_items_tracking_method` CHECK ((`tracking_method` in (_utf8mb4'NONE',_utf8mb4'LOT',_utf8mb4'SERIAL',_utf8mb4'LOT_AND_SERIAL'))),
  CONSTRAINT `chk_items_weight` CHECK (((`weight_per_base_uom` is null) or (`weight_per_base_uom` >= 0))),
  CONSTRAINT `chk_items_weight_uom` CHECK ((((`weight_per_base_uom` is null) and (`weight_uom_id` is null)) or ((`weight_per_base_uom` is not null) and (`weight_uom_id` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `membership_roles`
--

DROP TABLE IF EXISTS `membership_roles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `membership_roles` (
  `tenant_id` bigint NOT NULL,
  `membership_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`membership_id`,`role_id`),
  KEY `idx_membership_roles_role` (`role_id`,`tenant_id`),
  KEY `fk_membership_roles_membership` (`membership_id`,`tenant_id`),
  CONSTRAINT `fk_membership_roles_membership` FOREIGN KEY (`membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_membership_roles_role` FOREIGN KEY (`role_id`, `tenant_id`) REFERENCES `roles` (`id`, `tenant_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `outbound_shipment_allocations`
--

DROP TABLE IF EXISTS `outbound_shipment_allocations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbound_shipment_allocations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `outbound_shipment_line_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `location_id` bigint NOT NULL,
  `lot_id` bigint DEFAULT NULL,
  `lot_key` bigint GENERATED ALWAYS AS (coalesce(`lot_id`,0)) STORED,
  `allocated_qty` decimal(19,4) NOT NULL,
  `picked_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `shipped_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `status` varchar(30) NOT NULL DEFAULT 'ALLOCATED',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbound_shipment_allocations_id_tenant_item` (`id`,`tenant_id`,`item_id`),
  UNIQUE KEY `uk_outbound_shipment_allocations_picking_scope` (`id`,`tenant_id`,`outbound_shipment_line_id`,`item_id`,`warehouse_id`,`location_id`),
  UNIQUE KEY `uk_outbound_shipment_allocations_bucket` (`tenant_id`,`outbound_shipment_line_id`,`warehouse_id`,`location_id`,`lot_key`),
  KEY `idx_outbound_shipment_allocations_location` (`tenant_id`,`warehouse_id`,`location_id`),
  KEY `idx_outbound_shipment_allocations_lot` (`tenant_id`,`lot_id`),
  KEY `fk_outbound_shipment_allocations_line` (`outbound_shipment_line_id`,`tenant_id`,`item_id`),
  KEY `fk_outbound_shipment_allocations_location` (`location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_outbound_shipment_allocations_lot` (`lot_id`,`tenant_id`,`item_id`),
  CONSTRAINT `fk_outbound_shipment_allocations_line` FOREIGN KEY (`outbound_shipment_line_id`, `tenant_id`, `item_id`) REFERENCES `outbound_shipment_lines` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipment_allocations_location` FOREIGN KEY (`location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipment_allocations_lot` FOREIGN KEY (`lot_id`, `tenant_id`, `item_id`) REFERENCES `inventory_lots` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_outbound_shipment_allocations_qty` CHECK (((`allocated_qty` > 0) and (`picked_qty` >= 0) and (`shipped_qty` >= 0) and (`shipped_qty` <= `picked_qty`) and (`picked_qty` <= `allocated_qty`))),
  CONSTRAINT `chk_outbound_shipment_allocations_status` CHECK ((`status` in (_utf8mb4'ALLOCATED',_utf8mb4'PICKING',_utf8mb4'PICKED',_utf8mb4'PARTIALLY_SHIPPED',_utf8mb4'SHIPPED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `outbound_shipment_line_serials`
--

DROP TABLE IF EXISTS `outbound_shipment_line_serials`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbound_shipment_line_serials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `outbound_shipment_allocation_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `serial_id` bigint NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ALLOCATED',
  `picked_at` datetime(6) DEFAULT NULL,
  `shipped_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbound_shipment_line_serials_allocation` (`tenant_id`,`outbound_shipment_allocation_id`,`serial_id`),
  UNIQUE KEY `uk_outbound_shipment_line_serials_picking_scope` (`id`,`tenant_id`,`outbound_shipment_allocation_id`,`item_id`,`serial_id`),
  KEY `idx_outbound_shipment_line_serials_allocation` (`tenant_id`,`outbound_shipment_allocation_id`),
  KEY `fk_outbound_shipment_line_serials_allocation` (`outbound_shipment_allocation_id`,`tenant_id`,`item_id`),
  KEY `fk_outbound_shipment_line_serials_serial` (`serial_id`,`tenant_id`,`item_id`),
  KEY `idx_outbound_shipment_line_serials_serial` (`tenant_id`,`serial_id`),
  CONSTRAINT `fk_outbound_shipment_line_serials_allocation` FOREIGN KEY (`outbound_shipment_allocation_id`, `tenant_id`, `item_id`) REFERENCES `outbound_shipment_allocations` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipment_line_serials_serial` FOREIGN KEY (`serial_id`, `tenant_id`, `item_id`) REFERENCES `inventory_serials` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_outbound_shipment_line_serials_status` CHECK ((`status` in (_utf8mb4'ALLOCATED',_utf8mb4'PICKED',_utf8mb4'SHIPPED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `outbound_shipment_lines`
--

DROP TABLE IF EXISTS `outbound_shipment_lines`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbound_shipment_lines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `outbound_shipment_id` bigint NOT NULL,
  `line_number` int NOT NULL,
  `item_id` bigint NOT NULL,
  `uom_id` bigint NOT NULL,
  `requested_qty` decimal(19,4) NOT NULL,
  `allocated_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `picked_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `shipped_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `cancelled_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbound_shipment_lines_public_id` (`public_id`),
  UNIQUE KEY `uk_outbound_shipment_lines_number` (`tenant_id`,`outbound_shipment_id`,`line_number`),
  UNIQUE KEY `uk_outbound_shipment_lines_id_tenant_item` (`id`,`tenant_id`,`item_id`),
  UNIQUE KEY `uk_outbound_shipment_lines_shipment_item` (`id`,`tenant_id`,`outbound_shipment_id`,`item_id`),
  KEY `idx_outbound_shipment_lines_item` (`tenant_id`,`item_id`),
  KEY `fk_outbound_shipment_lines_header` (`outbound_shipment_id`,`tenant_id`),
  KEY `fk_outbound_shipment_lines_item` (`item_id`,`tenant_id`),
  KEY `fk_outbound_shipment_lines_uom` (`uom_id`),
  CONSTRAINT `fk_outbound_shipment_lines_header` FOREIGN KEY (`outbound_shipment_id`, `tenant_id`) REFERENCES `outbound_shipments` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipment_lines_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipment_lines_uom` FOREIGN KEY (`uom_id`) REFERENCES `units_of_measure` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_outbound_shipment_lines_number` CHECK ((`line_number` > 0)),
  CONSTRAINT `chk_outbound_shipment_lines_quantities` CHECK (((`requested_qty` > 0) and (`allocated_qty` >= 0) and (`picked_qty` >= 0) and (`shipped_qty` >= 0) and (`cancelled_qty` >= 0) and (`shipped_qty` <= `picked_qty`) and (`picked_qty` <= `allocated_qty`) and ((`allocated_qty` + `cancelled_qty`) <= `requested_qty`))),
  CONSTRAINT `chk_outbound_shipment_lines_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'ALLOCATED',_utf8mb4'PICKING',_utf8mb4'PICKED',_utf8mb4'PARTIALLY_SHIPPED',_utf8mb4'SHIPPED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `outbound_shipments`
--

DROP TABLE IF EXISTS `outbound_shipments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbound_shipments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `customer_partner_id` bigint NOT NULL,
  `customer_address_id` bigint DEFAULT NULL,
  `document_number` varchar(60) NOT NULL,
  `external_reference` varchar(100) DEFAULT NULL,
  `shipment_type` varchar(30) NOT NULL DEFAULT 'SALES',
  `status` varchar(30) NOT NULL DEFAULT 'DRAFT',
  `scheduled_at` datetime(6) DEFAULT NULL,
  `picked_at` datetime(6) DEFAULT NULL,
  `packed_at` datetime(6) DEFAULT NULL,
  `shipped_at` datetime(6) DEFAULT NULL,
  `posted_at` datetime(6) DEFAULT NULL,
  `shipping_carrier_partner_id` bigint DEFAULT NULL,
  `tracking_number` varchar(120) DEFAULT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `picked_by_membership_id` bigint DEFAULT NULL,
  `shipped_by_membership_id` bigint DEFAULT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbound_shipments_public_id` (`public_id`),
  UNIQUE KEY `uk_outbound_shipments_tenant_document` (`tenant_id`,`document_number`),
  UNIQUE KEY `uk_outbound_shipments_id_tenant` (`id`,`tenant_id`),
  UNIQUE KEY `uk_outbound_shipments_id_tenant_warehouse` (`id`,`tenant_id`,`warehouse_id`),
  KEY `idx_outbound_shipments_customer` (`tenant_id`,`customer_partner_id`),
  KEY `idx_outbound_shipments_warehouse_status` (`tenant_id`,`warehouse_id`,`status`),
  KEY `idx_outbound_shipments_scheduled` (`tenant_id`,`scheduled_at`),
  KEY `idx_outbound_shipments_tracking` (`tenant_id`,`tracking_number`),
  KEY `fk_outbound_shipments_warehouse` (`warehouse_id`,`tenant_id`),
  KEY `fk_outbound_shipments_customer` (`customer_partner_id`,`tenant_id`),
  KEY `fk_outbound_shipments_customer_address` (`customer_address_id`,`tenant_id`,`customer_partner_id`),
  KEY `fk_outbound_shipments_carrier` (`shipping_carrier_partner_id`,`tenant_id`),
  KEY `fk_outbound_shipments_created_by` (`created_by_membership_id`,`tenant_id`),
  KEY `fk_outbound_shipments_picked_by` (`picked_by_membership_id`,`tenant_id`),
  KEY `fk_outbound_shipments_shipped_by` (`shipped_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_outbound_shipments_carrier` FOREIGN KEY (`shipping_carrier_partner_id`, `tenant_id`) REFERENCES `business_partners` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipments_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipments_customer` FOREIGN KEY (`customer_partner_id`, `tenant_id`) REFERENCES `business_partners` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipments_customer_address` FOREIGN KEY (`customer_address_id`, `tenant_id`, `customer_partner_id`) REFERENCES `business_partner_addresses` (`id`, `tenant_id`, `business_partner_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipments_picked_by` FOREIGN KEY (`picked_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipments_shipped_by` FOREIGN KEY (`shipped_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_outbound_shipments_warehouse` FOREIGN KEY (`warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_outbound_shipments_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'RELEASED',_utf8mb4'PICKING',_utf8mb4'PICKED',_utf8mb4'PACKED',_utf8mb4'SHIPPED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_outbound_shipments_type` CHECK ((`shipment_type` in (_utf8mb4'SALES',_utf8mb4'TRANSFER_OUT',_utf8mb4'RETURN_TO_SUPPLIER',_utf8mb4'OTHER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `packing_confirmations`
--

DROP TABLE IF EXISTS `packing_confirmations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `packing_confirmations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `packing_package_line_id` bigint NOT NULL,
  `packing_package_id` bigint NOT NULL,
  `packing_session_id` bigint NOT NULL,
  `outbound_shipment_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `outbound_shipment_allocation_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `confirmation_type` varchar(20) NOT NULL DEFAULT 'PACK',
  `confirmed_qty` decimal(19,4) NOT NULL,
  `request_id` char(36) NOT NULL,
  `confirmed_by_membership_id` bigint NOT NULL,
  `confirmed_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `notes` text,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_packing_confirmations_public_id` (`public_id`),
  UNIQUE KEY `uk_packing_confirmations_request` (`tenant_id`,`request_id`),
  KEY `idx_packing_confirmations_package_line` (`tenant_id`,`packing_package_line_id`,`confirmed_at`),
  KEY `idx_packing_confirmations_package` (`tenant_id`,`packing_package_id`),
  KEY `idx_packing_confirmations_session` (`tenant_id`,`packing_session_id`),
  KEY `idx_packing_confirmations_actor` (`tenant_id`,`confirmed_by_membership_id`,`confirmed_at`),
  KEY `fk_packing_confirmations_package_line` (`packing_package_line_id`,`tenant_id`,`packing_package_id`,`packing_session_id`,`outbound_shipment_id`,`warehouse_id`,`outbound_shipment_allocation_id`,`item_id`),
  KEY `fk_packing_confirmations_actor` (`confirmed_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_packing_confirmations_actor` FOREIGN KEY (`confirmed_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_packing_confirmations_package_line` FOREIGN KEY (`packing_package_line_id`, `tenant_id`, `packing_package_id`, `packing_session_id`, `outbound_shipment_id`, `warehouse_id`, `outbound_shipment_allocation_id`, `item_id`) REFERENCES `packing_package_lines` (`id`, `tenant_id`, `packing_package_id`, `packing_session_id`, `outbound_shipment_id`, `warehouse_id`, `outbound_shipment_allocation_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_packing_confirmations_qty` CHECK ((`confirmed_qty` > 0)),
  CONSTRAINT `chk_packing_confirmations_type` CHECK ((`confirmation_type` in (_utf8mb4'PACK',_utf8mb4'REVERSAL')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `packing_package_lines`
--

DROP TABLE IF EXISTS `packing_package_lines`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `packing_package_lines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `packing_package_id` bigint NOT NULL,
  `packing_session_id` bigint NOT NULL,
  `outbound_shipment_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `picking_task_line_id` bigint NOT NULL,
  `outbound_shipment_allocation_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `line_number` int NOT NULL,
  `packed_qty` decimal(19,4) NOT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_packing_package_lines_number` (`tenant_id`,`packing_package_id`,`line_number`),
  UNIQUE KEY `uk_packing_package_lines_scope` (`id`,`tenant_id`,`packing_package_id`,`packing_session_id`,`outbound_shipment_id`,`warehouse_id`,`outbound_shipment_allocation_id`,`item_id`),
  KEY `idx_packing_package_lines_picking` (`tenant_id`,`picking_task_line_id`),
  KEY `idx_packing_package_lines_allocation` (`tenant_id`,`outbound_shipment_allocation_id`),
  KEY `fk_packing_package_lines_package` (`packing_package_id`,`tenant_id`,`packing_session_id`,`outbound_shipment_id`,`warehouse_id`),
  KEY `fk_packing_package_lines_picking` (`picking_task_line_id`,`tenant_id`,`outbound_shipment_id`,`warehouse_id`,`outbound_shipment_allocation_id`,`item_id`),
  CONSTRAINT `fk_packing_package_lines_package` FOREIGN KEY (`packing_package_id`, `tenant_id`, `packing_session_id`, `outbound_shipment_id`, `warehouse_id`) REFERENCES `packing_packages` (`id`, `tenant_id`, `packing_session_id`, `outbound_shipment_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_packing_package_lines_picking` FOREIGN KEY (`picking_task_line_id`, `tenant_id`, `outbound_shipment_id`, `warehouse_id`, `outbound_shipment_allocation_id`, `item_id`) REFERENCES `picking_task_lines` (`id`, `tenant_id`, `outbound_shipment_id`, `warehouse_id`, `outbound_shipment_allocation_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_packing_package_lines_number` CHECK ((`line_number` > 0)),
  CONSTRAINT `chk_packing_package_lines_qty` CHECK ((`packed_qty` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `packing_package_serials`
--

DROP TABLE IF EXISTS `packing_package_serials`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `packing_package_serials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `packing_package_line_id` bigint NOT NULL,
  `packing_package_id` bigint NOT NULL,
  `packing_session_id` bigint NOT NULL,
  `outbound_shipment_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `picking_task_line_serial_id` bigint NOT NULL,
  `picking_task_line_id` bigint NOT NULL,
  `outbound_shipment_allocation_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `serial_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_packing_package_serials_source` (`tenant_id`,`picking_task_line_serial_id`),
  UNIQUE KEY `uk_packing_package_serials_package_serial` (`tenant_id`,`packing_package_id`,`serial_id`),
  KEY `idx_packing_package_serials_serial` (`tenant_id`,`serial_id`),
  KEY `fk_packing_package_serials_package_line` (`packing_package_line_id`,`tenant_id`,`packing_package_id`,`packing_session_id`,`outbound_shipment_id`,`warehouse_id`,`outbound_shipment_allocation_id`,`item_id`),
  KEY `fk_packing_package_serials_picked_serial` (`picking_task_line_serial_id`,`tenant_id`,`picking_task_line_id`,`outbound_shipment_allocation_id`,`item_id`,`serial_id`),
  CONSTRAINT `fk_packing_package_serials_package_line` FOREIGN KEY (`packing_package_line_id`, `tenant_id`, `packing_package_id`, `packing_session_id`, `outbound_shipment_id`, `warehouse_id`, `outbound_shipment_allocation_id`, `item_id`) REFERENCES `packing_package_lines` (`id`, `tenant_id`, `packing_package_id`, `packing_session_id`, `outbound_shipment_id`, `warehouse_id`, `outbound_shipment_allocation_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_packing_package_serials_picked_serial` FOREIGN KEY (`picking_task_line_serial_id`, `tenant_id`, `picking_task_line_id`, `outbound_shipment_allocation_id`, `item_id`, `serial_id`) REFERENCES `picking_task_line_serials` (`id`, `tenant_id`, `picking_task_line_id`, `outbound_shipment_allocation_id`, `item_id`, `serial_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `packing_packages`
--

DROP TABLE IF EXISTS `packing_packages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `packing_packages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `packing_session_id` bigint NOT NULL,
  `outbound_shipment_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `package_number` varchar(60) NOT NULL,
  `package_type` varchar(30) NOT NULL DEFAULT 'CARTON',
  `status` varchar(20) NOT NULL DEFAULT 'OPEN',
  `weight` decimal(19,4) DEFAULT NULL,
  `length` decimal(12,3) DEFAULT NULL,
  `width` decimal(12,3) DEFAULT NULL,
  `height` decimal(12,3) DEFAULT NULL,
  `weight_uom_id` bigint DEFAULT NULL,
  `sealed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_packing_packages_public_id` (`public_id`),
  UNIQUE KEY `uk_packing_packages_number` (`tenant_id`,`packing_session_id`,`package_number`),
  UNIQUE KEY `uk_packing_packages_session_scope` (`id`,`tenant_id`,`packing_session_id`,`outbound_shipment_id`,`warehouse_id`),
  KEY `idx_packing_packages_shipment` (`tenant_id`,`outbound_shipment_id`),
  KEY `idx_packing_packages_session_status` (`tenant_id`,`packing_session_id`,`status`),
  KEY `fk_packing_packages_session` (`packing_session_id`,`tenant_id`,`outbound_shipment_id`,`warehouse_id`),
  KEY `fk_packing_packages_weight_uom` (`weight_uom_id`),
  CONSTRAINT `fk_packing_packages_session` FOREIGN KEY (`packing_session_id`, `tenant_id`, `outbound_shipment_id`, `warehouse_id`) REFERENCES `packing_sessions` (`id`, `tenant_id`, `outbound_shipment_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_packing_packages_weight_uom` FOREIGN KEY (`weight_uom_id`) REFERENCES `units_of_measure` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_packing_packages_measurements` CHECK ((((`weight` is null) or (`weight` > 0)) and ((`length` is null) or (`length` > 0)) and ((`width` is null) or (`width` > 0)) and ((`height` is null) or (`height` > 0)))),
  CONSTRAINT `chk_packing_packages_status` CHECK ((`status` in (_utf8mb4'OPEN',_utf8mb4'SEALED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_packing_packages_type` CHECK ((`package_type` in (_utf8mb4'CARTON',_utf8mb4'PALLET',_utf8mb4'CRATE',_utf8mb4'ENVELOPE',_utf8mb4'BAG',_utf8mb4'OTHER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `packing_sessions`
--

DROP TABLE IF EXISTS `packing_sessions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `packing_sessions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `outbound_shipment_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `packing_number` varchar(60) NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'DRAFT',
  `assigned_to_membership_id` bigint DEFAULT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `cancelled_at` datetime(6) DEFAULT NULL,
  `notes` text,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_packing_sessions_public_id` (`public_id`),
  UNIQUE KEY `uk_packing_sessions_number` (`tenant_id`,`packing_number`),
  UNIQUE KEY `uk_packing_sessions_shipment_scope` (`id`,`tenant_id`,`outbound_shipment_id`,`warehouse_id`),
  KEY `idx_packing_sessions_shipment` (`tenant_id`,`outbound_shipment_id`),
  KEY `idx_packing_sessions_warehouse_status` (`tenant_id`,`warehouse_id`,`status`),
  KEY `idx_packing_sessions_assigned_to` (`tenant_id`,`assigned_to_membership_id`,`status`),
  KEY `fk_packing_sessions_shipment` (`outbound_shipment_id`,`tenant_id`,`warehouse_id`),
  KEY `fk_packing_sessions_assigned_to` (`assigned_to_membership_id`,`tenant_id`),
  KEY `fk_packing_sessions_created_by` (`created_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_packing_sessions_assigned_to` FOREIGN KEY (`assigned_to_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_packing_sessions_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_packing_sessions_shipment` FOREIGN KEY (`outbound_shipment_id`, `tenant_id`, `warehouse_id`) REFERENCES `outbound_shipments` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_packing_sessions_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'READY',_utf8mb4'IN_PROGRESS',_utf8mb4'COMPLETED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `permissions`
--

DROP TABLE IF EXISTS `permissions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `permissions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `permission_code` varchar(120) NOT NULL,
  `module` varchar(60) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permissions_code` (`permission_code`),
  KEY `idx_permissions_module` (`module`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `picking_confirmations`
--

DROP TABLE IF EXISTS `picking_confirmations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `picking_confirmations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `picking_task_line_id` bigint NOT NULL,
  `outbound_shipment_allocation_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `confirmation_type` varchar(20) NOT NULL DEFAULT 'PICK',
  `confirmed_qty` decimal(19,4) NOT NULL,
  `request_id` char(36) NOT NULL,
  `confirmed_by_membership_id` bigint NOT NULL,
  `confirmed_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `notes` text,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_picking_confirmations_public_id` (`public_id`),
  UNIQUE KEY `uk_picking_confirmations_request` (`tenant_id`,`request_id`),
  KEY `idx_picking_confirmations_task_line` (`tenant_id`,`picking_task_line_id`,`confirmed_at`),
  KEY `idx_picking_confirmations_allocation` (`tenant_id`,`outbound_shipment_allocation_id`),
  KEY `idx_picking_confirmations_actor` (`tenant_id`,`confirmed_by_membership_id`,`confirmed_at`),
  KEY `fk_picking_confirmations_task_line` (`picking_task_line_id`,`tenant_id`,`outbound_shipment_allocation_id`,`item_id`),
  KEY `fk_picking_confirmations_actor` (`confirmed_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_picking_confirmations_actor` FOREIGN KEY (`confirmed_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_picking_confirmations_task_line` FOREIGN KEY (`picking_task_line_id`, `tenant_id`, `outbound_shipment_allocation_id`, `item_id`) REFERENCES `picking_task_lines` (`id`, `tenant_id`, `outbound_shipment_allocation_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_picking_confirmations_qty` CHECK ((`confirmed_qty` > 0)),
  CONSTRAINT `chk_picking_confirmations_type` CHECK ((`confirmation_type` in (_utf8mb4'PICK',_utf8mb4'REVERSAL')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `picking_task_line_serials`
--

DROP TABLE IF EXISTS `picking_task_line_serials`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `picking_task_line_serials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `picking_task_line_id` bigint NOT NULL,
  `outbound_shipment_line_serial_id` bigint NOT NULL,
  `outbound_shipment_allocation_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `serial_id` bigint NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ALLOCATED',
  `picked_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_picking_task_line_serials_source` (`tenant_id`,`outbound_shipment_line_serial_id`),
  UNIQUE KEY `uk_picking_task_line_serials_line_serial` (`tenant_id`,`picking_task_line_id`,`serial_id`),
  UNIQUE KEY `uk_picking_task_line_serials_packing_scope` (`id`,`tenant_id`,`picking_task_line_id`,`outbound_shipment_allocation_id`,`item_id`,`serial_id`),
  KEY `idx_picking_task_line_serials_serial` (`tenant_id`,`serial_id`),
  KEY `fk_picking_task_line_serials_task_line` (`picking_task_line_id`,`tenant_id`,`outbound_shipment_allocation_id`,`item_id`),
  KEY `fk_picking_task_line_serials_shipment_serial` (`outbound_shipment_line_serial_id`,`tenant_id`,`outbound_shipment_allocation_id`,`item_id`,`serial_id`),
  CONSTRAINT `fk_picking_task_line_serials_shipment_serial` FOREIGN KEY (`outbound_shipment_line_serial_id`, `tenant_id`, `outbound_shipment_allocation_id`, `item_id`, `serial_id`) REFERENCES `outbound_shipment_line_serials` (`id`, `tenant_id`, `outbound_shipment_allocation_id`, `item_id`, `serial_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_picking_task_line_serials_task_line` FOREIGN KEY (`picking_task_line_id`, `tenant_id`, `outbound_shipment_allocation_id`, `item_id`) REFERENCES `picking_task_lines` (`id`, `tenant_id`, `outbound_shipment_allocation_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_picking_task_line_serials_status` CHECK ((`status` in (_utf8mb4'ALLOCATED',_utf8mb4'PICKED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `picking_task_lines`
--

DROP TABLE IF EXISTS `picking_task_lines`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `picking_task_lines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `picking_task_id` bigint NOT NULL,
  `outbound_shipment_id` bigint NOT NULL,
  `outbound_shipment_line_id` bigint NOT NULL,
  `outbound_shipment_allocation_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `source_location_id` bigint NOT NULL,
  `line_number` int NOT NULL,
  `planned_qty` decimal(19,4) NOT NULL,
  `picked_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `started_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `notes` text,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_picking_task_lines_task_allocation` (`tenant_id`,`picking_task_id`,`outbound_shipment_allocation_id`),
  UNIQUE KEY `uk_picking_task_lines_number` (`tenant_id`,`picking_task_id`,`line_number`),
  UNIQUE KEY `uk_picking_task_lines_serial_scope` (`id`,`tenant_id`,`outbound_shipment_allocation_id`,`item_id`),
  UNIQUE KEY `uk_picking_task_lines_packing_scope` (`id`,`tenant_id`,`outbound_shipment_id`,`warehouse_id`,`outbound_shipment_allocation_id`,`item_id`),
  KEY `idx_picking_task_lines_task_status` (`tenant_id`,`picking_task_id`,`status`),
  KEY `idx_picking_task_lines_location` (`tenant_id`,`warehouse_id`,`source_location_id`),
  KEY `idx_picking_task_lines_item` (`tenant_id`,`item_id`),
  KEY `idx_picking_task_lines_shipment_line` (`tenant_id`,`outbound_shipment_line_id`),
  KEY `fk_picking_task_lines_task` (`picking_task_id`,`tenant_id`,`outbound_shipment_id`,`warehouse_id`),
  KEY `fk_picking_task_lines_shipment_line` (`outbound_shipment_line_id`,`tenant_id`,`outbound_shipment_id`,`item_id`),
  KEY `fk_picking_task_lines_allocation` (`outbound_shipment_allocation_id`,`tenant_id`,`outbound_shipment_line_id`,`item_id`,`warehouse_id`,`source_location_id`),
  CONSTRAINT `fk_picking_task_lines_allocation` FOREIGN KEY (`outbound_shipment_allocation_id`, `tenant_id`, `outbound_shipment_line_id`, `item_id`, `warehouse_id`, `source_location_id`) REFERENCES `outbound_shipment_allocations` (`id`, `tenant_id`, `outbound_shipment_line_id`, `item_id`, `warehouse_id`, `location_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_picking_task_lines_shipment_line` FOREIGN KEY (`outbound_shipment_line_id`, `tenant_id`, `outbound_shipment_id`, `item_id`) REFERENCES `outbound_shipment_lines` (`id`, `tenant_id`, `outbound_shipment_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_picking_task_lines_task` FOREIGN KEY (`picking_task_id`, `tenant_id`, `outbound_shipment_id`, `warehouse_id`) REFERENCES `picking_tasks` (`id`, `tenant_id`, `outbound_shipment_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_picking_task_lines_number` CHECK ((`line_number` > 0)),
  CONSTRAINT `chk_picking_task_lines_quantities` CHECK (((`planned_qty` > 0) and (`picked_qty` >= 0) and (`picked_qty` <= `planned_qty`))),
  CONSTRAINT `chk_picking_task_lines_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'IN_PROGRESS',_utf8mb4'PARTIALLY_PICKED',_utf8mb4'PICKED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `picking_tasks`
--

DROP TABLE IF EXISTS `picking_tasks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `picking_tasks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `outbound_shipment_id` bigint NOT NULL,
  `picking_wave_id` bigint DEFAULT NULL,
  `task_number` varchar(60) NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'DRAFT',
  `priority` varchar(20) NOT NULL DEFAULT 'NORMAL',
  `assigned_to_membership_id` bigint DEFAULT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `assigned_at` datetime(6) DEFAULT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `cancelled_at` datetime(6) DEFAULT NULL,
  `notes` text,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_picking_tasks_public_id` (`public_id`),
  UNIQUE KEY `uk_picking_tasks_tenant_number` (`tenant_id`,`task_number`),
  UNIQUE KEY `uk_picking_tasks_id_tenant_shipment_warehouse` (`id`,`tenant_id`,`outbound_shipment_id`,`warehouse_id`),
  KEY `idx_picking_tasks_warehouse_status` (`tenant_id`,`warehouse_id`,`status`),
  KEY `idx_picking_tasks_shipment` (`tenant_id`,`outbound_shipment_id`),
  KEY `idx_picking_tasks_wave` (`tenant_id`,`picking_wave_id`),
  KEY `idx_picking_tasks_assigned_to` (`tenant_id`,`assigned_to_membership_id`,`status`),
  KEY `fk_picking_tasks_shipment` (`outbound_shipment_id`,`tenant_id`,`warehouse_id`),
  KEY `fk_picking_tasks_wave_shipment` (`tenant_id`,`picking_wave_id`,`outbound_shipment_id`,`warehouse_id`),
  KEY `fk_picking_tasks_assigned_to` (`assigned_to_membership_id`,`tenant_id`),
  KEY `fk_picking_tasks_created_by` (`created_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_picking_tasks_assigned_to` FOREIGN KEY (`assigned_to_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_picking_tasks_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_picking_tasks_shipment` FOREIGN KEY (`outbound_shipment_id`, `tenant_id`, `warehouse_id`) REFERENCES `outbound_shipments` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_picking_tasks_wave_shipment` FOREIGN KEY (`tenant_id`, `picking_wave_id`, `outbound_shipment_id`, `warehouse_id`) REFERENCES `picking_wave_shipments` (`tenant_id`, `picking_wave_id`, `outbound_shipment_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_picking_tasks_priority` CHECK ((`priority` in (_utf8mb4'LOW',_utf8mb4'NORMAL',_utf8mb4'HIGH',_utf8mb4'URGENT'))),
  CONSTRAINT `chk_picking_tasks_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'READY',_utf8mb4'ASSIGNED',_utf8mb4'IN_PROGRESS',_utf8mb4'COMPLETED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `picking_wave_shipments`
--

DROP TABLE IF EXISTS `picking_wave_shipments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `picking_wave_shipments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `picking_wave_id` bigint NOT NULL,
  `outbound_shipment_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `added_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `started_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_picking_wave_shipments_scope` (`tenant_id`,`picking_wave_id`,`outbound_shipment_id`),
  UNIQUE KEY `uk_picking_wave_shipments_task_scope` (`tenant_id`,`picking_wave_id`,`outbound_shipment_id`,`warehouse_id`),
  KEY `idx_picking_wave_shipments_shipment` (`tenant_id`,`outbound_shipment_id`),
  KEY `fk_picking_wave_shipments_wave` (`picking_wave_id`,`tenant_id`,`warehouse_id`),
  KEY `fk_picking_wave_shipments_shipment` (`outbound_shipment_id`,`tenant_id`,`warehouse_id`),
  CONSTRAINT `fk_picking_wave_shipments_shipment` FOREIGN KEY (`outbound_shipment_id`, `tenant_id`, `warehouse_id`) REFERENCES `outbound_shipments` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_picking_wave_shipments_wave` FOREIGN KEY (`picking_wave_id`, `tenant_id`, `warehouse_id`) REFERENCES `picking_waves` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_picking_wave_shipments_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'IN_PROGRESS',_utf8mb4'COMPLETED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `picking_waves`
--

DROP TABLE IF EXISTS `picking_waves`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `picking_waves` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `wave_number` varchar(60) NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'DRAFT',
  `priority` varchar(20) NOT NULL DEFAULT 'NORMAL',
  `planned_start_at` datetime(6) DEFAULT NULL,
  `released_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `notes` text,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_picking_waves_public_id` (`public_id`),
  UNIQUE KEY `uk_picking_waves_tenant_number` (`tenant_id`,`wave_number`),
  UNIQUE KEY `uk_picking_waves_id_tenant_warehouse` (`id`,`tenant_id`,`warehouse_id`),
  KEY `idx_picking_waves_warehouse_status` (`tenant_id`,`warehouse_id`,`status`),
  KEY `idx_picking_waves_planned_start` (`tenant_id`,`planned_start_at`),
  KEY `fk_picking_waves_warehouse` (`warehouse_id`,`tenant_id`),
  KEY `fk_picking_waves_created_by` (`created_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_picking_waves_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_picking_waves_warehouse` FOREIGN KEY (`warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_picking_waves_priority` CHECK ((`priority` in (_utf8mb4'LOW',_utf8mb4'NORMAL',_utf8mb4'HIGH',_utf8mb4'URGENT'))),
  CONSTRAINT `chk_picking_waves_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'RELEASED',_utf8mb4'IN_PROGRESS',_utf8mb4'COMPLETED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `putaway_confirmations`
--

DROP TABLE IF EXISTS `putaway_confirmations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `putaway_confirmations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `putaway_task_id` bigint NOT NULL,
  `putaway_task_line_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `inbound_receipt_id` bigint NOT NULL,
  `inbound_receipt_line_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `source_location_id` bigint NOT NULL,
  `destination_location_id` bigint NOT NULL,
  `confirmed_qty` decimal(19,4) NOT NULL,
  `confirmed_by_membership_id` bigint NOT NULL,
  `request_id` char(36) NOT NULL,
  `confirmed_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_putaway_confirmations_public_id` (`public_id`),
  UNIQUE KEY `uk_putaway_confirmations_request` (`tenant_id`,`request_id`),
  UNIQUE KEY `uk_putaway_confirmations_id_tenant` (`id`,`tenant_id`),
  KEY `idx_putaway_confirmations_task` (`putaway_task_id`,`tenant_id`,`confirmed_at`),
  KEY `idx_putaway_confirmations_line` (`putaway_task_line_id`,`tenant_id`,`confirmed_at`),
  KEY `idx_putaway_confirmations_actor` (`confirmed_by_membership_id`,`tenant_id`),
  KEY `fk_putaway_confirmations_line` (`putaway_task_line_id`,`tenant_id`,`putaway_task_id`,`warehouse_id`,`inbound_receipt_id`,`inbound_receipt_line_id`,`item_id`,`source_location_id`,`destination_location_id`),
  CONSTRAINT `fk_putaway_confirmations_actor` FOREIGN KEY (`confirmed_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_putaway_confirmations_line` FOREIGN KEY (`putaway_task_line_id`, `tenant_id`, `putaway_task_id`, `warehouse_id`, `inbound_receipt_id`, `inbound_receipt_line_id`, `item_id`, `source_location_id`, `destination_location_id`) REFERENCES `putaway_task_lines` (`id`, `tenant_id`, `putaway_task_id`, `warehouse_id`, `inbound_receipt_id`, `inbound_receipt_line_id`, `item_id`, `source_location_id`, `destination_location_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_putaway_confirmations_qty` CHECK ((`confirmed_qty` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `putaway_task_line_serials`
--

DROP TABLE IF EXISTS `putaway_task_line_serials`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `putaway_task_line_serials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `putaway_task_line_id` bigint NOT NULL,
  `inbound_receipt_line_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `serial_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_putaway_task_line_serials_serial` (`tenant_id`,`serial_id`),
  UNIQUE KEY `uk_putaway_task_line_serials_line` (`tenant_id`,`putaway_task_line_id`,`serial_id`),
  KEY `idx_putaway_task_line_serials_task_line` (`putaway_task_line_id`,`tenant_id`),
  KEY `fk_putaway_task_line_serials_line` (`putaway_task_line_id`,`tenant_id`,`inbound_receipt_line_id`,`item_id`),
  KEY `fk_putaway_task_line_serials_receipt_serial` (`tenant_id`,`inbound_receipt_line_id`,`serial_id`),
  CONSTRAINT `fk_putaway_task_line_serials_line` FOREIGN KEY (`putaway_task_line_id`, `tenant_id`, `inbound_receipt_line_id`, `item_id`) REFERENCES `putaway_task_lines` (`id`, `tenant_id`, `inbound_receipt_line_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_putaway_task_line_serials_receipt_serial` FOREIGN KEY (`tenant_id`, `inbound_receipt_line_id`, `serial_id`) REFERENCES `inbound_receipt_line_serials` (`tenant_id`, `inbound_receipt_line_id`, `serial_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `putaway_task_lines`
--

DROP TABLE IF EXISTS `putaway_task_lines`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `putaway_task_lines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `putaway_task_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `inbound_receipt_id` bigint NOT NULL,
  `inbound_receipt_line_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `lot_id` bigint DEFAULT NULL,
  `source_location_id` bigint NOT NULL,
  `destination_location_id` bigint NOT NULL,
  `planned_qty` decimal(19,4) NOT NULL,
  `confirmed_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `lot_key` bigint GENERATED ALWAYS AS (coalesce(`lot_id`,0)) STORED,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_putaway_task_lines_public_id` (`public_id`),
  UNIQUE KEY `uk_putaway_task_lines_id_tenant` (`id`,`tenant_id`),
  UNIQUE KEY `uk_putaway_task_lines_serial_scope` (`id`,`tenant_id`,`inbound_receipt_line_id`,`item_id`),
  UNIQUE KEY `uk_putaway_task_lines_confirmation_scope` (`id`,`tenant_id`,`putaway_task_id`,`warehouse_id`,`inbound_receipt_id`,`inbound_receipt_line_id`,`item_id`,`source_location_id`,`destination_location_id`),
  UNIQUE KEY `uk_putaway_task_lines_allocation` (`tenant_id`,`putaway_task_id`,`inbound_receipt_line_id`,`lot_key`,`destination_location_id`),
  KEY `idx_putaway_task_lines_task` (`putaway_task_id`,`status`),
  KEY `idx_putaway_task_lines_receipt_line` (`tenant_id`,`inbound_receipt_line_id`),
  KEY `idx_putaway_task_lines_destination` (`tenant_id`,`warehouse_id`,`destination_location_id`),
  KEY `idx_putaway_task_lines_item` (`tenant_id`,`item_id`,`status`),
  KEY `fk_putaway_task_lines_item` (`item_id`,`tenant_id`),
  KEY `fk_putaway_task_lines_lot` (`lot_id`,`tenant_id`,`item_id`),
  KEY `fk_putaway_task_lines_source` (`source_location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_putaway_task_lines_destination` (`destination_location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_putaway_task_lines_task` (`putaway_task_id`,`tenant_id`,`inbound_receipt_id`,`warehouse_id`),
  KEY `fk_putaway_task_lines_receipt_line` (`inbound_receipt_line_id`,`tenant_id`,`inbound_receipt_id`,`item_id`),
  CONSTRAINT `fk_putaway_task_lines_destination` FOREIGN KEY (`destination_location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_putaway_task_lines_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_putaway_task_lines_lot` FOREIGN KEY (`lot_id`, `tenant_id`, `item_id`) REFERENCES `inventory_lots` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_putaway_task_lines_receipt_line` FOREIGN KEY (`inbound_receipt_line_id`, `tenant_id`, `inbound_receipt_id`, `item_id`) REFERENCES `inbound_receipt_lines` (`id`, `tenant_id`, `inbound_receipt_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_putaway_task_lines_source` FOREIGN KEY (`source_location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_putaway_task_lines_task` FOREIGN KEY (`putaway_task_id`, `tenant_id`, `inbound_receipt_id`, `warehouse_id`) REFERENCES `putaway_tasks` (`id`, `tenant_id`, `inbound_receipt_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_putaway_task_lines_confirmed_qty` CHECK (((`confirmed_qty` >= 0) and (`confirmed_qty` <= `planned_qty`))),
  CONSTRAINT `chk_putaway_task_lines_different_location` CHECK ((`source_location_id` <> `destination_location_id`)),
  CONSTRAINT `chk_putaway_task_lines_planned_qty` CHECK ((`planned_qty` > 0)),
  CONSTRAINT `chk_putaway_task_lines_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'IN_PROGRESS',_utf8mb4'COMPLETED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `putaway_tasks`
--

DROP TABLE IF EXISTS `putaway_tasks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `putaway_tasks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `inbound_receipt_id` bigint NOT NULL,
  `task_number` varchar(60) NOT NULL,
  `priority` varchar(20) NOT NULL DEFAULT 'NORMAL',
  `status` varchar(30) NOT NULL DEFAULT 'DRAFT',
  `assigned_to_membership_id` bigint DEFAULT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `planned_at` datetime(6) DEFAULT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `cancelled_at` datetime(6) DEFAULT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_putaway_tasks_public_id` (`public_id`),
  UNIQUE KEY `uk_putaway_tasks_tenant_number` (`tenant_id`,`task_number`),
  UNIQUE KEY `uk_putaway_tasks_id_tenant_warehouse` (`id`,`tenant_id`,`warehouse_id`),
  UNIQUE KEY `uk_putaway_tasks_receipt_scope` (`id`,`tenant_id`,`inbound_receipt_id`,`warehouse_id`),
  KEY `idx_putaway_tasks_receipt` (`tenant_id`,`inbound_receipt_id`),
  KEY `idx_putaway_tasks_status` (`tenant_id`,`warehouse_id`,`status`,`priority`),
  KEY `idx_putaway_tasks_assigned` (`tenant_id`,`assigned_to_membership_id`,`status`),
  KEY `fk_putaway_tasks_receipt` (`inbound_receipt_id`,`tenant_id`,`warehouse_id`),
  KEY `fk_putaway_tasks_warehouse` (`warehouse_id`,`tenant_id`),
  KEY `fk_putaway_tasks_assigned` (`assigned_to_membership_id`,`tenant_id`),
  KEY `fk_putaway_tasks_created_by` (`created_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_putaway_tasks_assigned` FOREIGN KEY (`assigned_to_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_putaway_tasks_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_putaway_tasks_receipt` FOREIGN KEY (`inbound_receipt_id`, `tenant_id`, `warehouse_id`) REFERENCES `inbound_receipts` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_putaway_tasks_warehouse` FOREIGN KEY (`warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_putaway_tasks_priority` CHECK ((`priority` in (_utf8mb4'LOW',_utf8mb4'NORMAL',_utf8mb4'HIGH',_utf8mb4'URGENT'))),
  CONSTRAINT `chk_putaway_tasks_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'READY',_utf8mb4'ASSIGNED',_utf8mb4'IN_PROGRESS',_utf8mb4'COMPLETED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `replenishment_confirmations`
--

DROP TABLE IF EXISTS `replenishment_confirmations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `replenishment_confirmations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `replenishment_task_line_id` bigint NOT NULL,
  `replenishment_task_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `source_location_id` bigint NOT NULL,
  `destination_location_id` bigint NOT NULL,
  `confirmation_type` varchar(20) NOT NULL,
  `confirmed_qty` decimal(19,4) NOT NULL,
  `reverses_confirmation_id` bigint DEFAULT NULL,
  `request_id` varchar(100) NOT NULL,
  `confirmed_by_membership_id` bigint NOT NULL,
  `confirmed_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_replenishment_confirmations_public_id` (`public_id`),
  UNIQUE KEY `uk_replenishment_confirmations_request` (`tenant_id`,`request_id`),
  UNIQUE KEY `uk_replenishment_confirmations_id_tenant` (`id`,`tenant_id`),
  KEY `idx_replenishment_confirmations_line` (`tenant_id`,`replenishment_task_line_id`,`confirmed_at`),
  KEY `idx_replenishment_confirmations_task` (`tenant_id`,`replenishment_task_id`,`confirmation_type`),
  KEY `idx_replenishment_confirmations_reversal` (`tenant_id`,`reverses_confirmation_id`),
  KEY `idx_replenishment_confirmations_actor` (`tenant_id`,`confirmed_by_membership_id`),
  KEY `fk_replenishment_confirmations_line` (`replenishment_task_line_id`,`tenant_id`,`replenishment_task_id`,`warehouse_id`,`item_id`,`source_location_id`,`destination_location_id`),
  KEY `fk_replenishment_confirmations_reversal` (`reverses_confirmation_id`,`tenant_id`),
  KEY `fk_replenishment_confirmations_actor` (`confirmed_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_replenishment_confirmations_actor` FOREIGN KEY (`confirmed_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_confirmations_line` FOREIGN KEY (`replenishment_task_line_id`, `tenant_id`, `replenishment_task_id`, `warehouse_id`, `item_id`, `source_location_id`, `destination_location_id`) REFERENCES `replenishment_task_lines` (`id`, `tenant_id`, `replenishment_task_id`, `warehouse_id`, `item_id`, `source_location_id`, `destination_location_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_confirmations_reversal` FOREIGN KEY (`reverses_confirmation_id`, `tenant_id`) REFERENCES `replenishment_confirmations` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_replenishment_confirmations_qty` CHECK ((`confirmed_qty` > 0)),
  CONSTRAINT `chk_replenishment_confirmations_reversal` CHECK ((((`confirmation_type` = _utf8mb4'MOVE') and (`reverses_confirmation_id` is null)) or ((`confirmation_type` = _utf8mb4'REVERSAL') and (`reverses_confirmation_id` is not null)))),
  CONSTRAINT `chk_replenishment_confirmations_type` CHECK ((`confirmation_type` in (_utf8mb4'MOVE',_utf8mb4'REVERSAL')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `replenishment_rules`
--

DROP TABLE IF EXISTS `replenishment_rules`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `replenishment_rules` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `source_location_id` bigint NOT NULL,
  `destination_location_id` bigint NOT NULL,
  `rule_name` varchar(150) NOT NULL,
  `rule_type` varchar(30) NOT NULL DEFAULT 'MIN_MAX',
  `minimum_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `replenish_to_qty` decimal(19,4) NOT NULL,
  `maximum_replenishment_qty` decimal(19,4) DEFAULT NULL,
  `priority` varchar(20) NOT NULL DEFAULT 'NORMAL',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_by_membership_id` bigint NOT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_replenishment_rules_public_id` (`public_id`),
  UNIQUE KEY `uk_replenishment_rules_location_item` (`tenant_id`,`warehouse_id`,`item_id`,`source_location_id`,`destination_location_id`),
  UNIQUE KEY `uk_replenishment_rules_id_scope` (`id`,`tenant_id`,`warehouse_id`),
  KEY `idx_replenishment_rules_item` (`tenant_id`,`item_id`,`is_active`),
  KEY `idx_replenishment_rules_destination` (`tenant_id`,`warehouse_id`,`destination_location_id`,`is_active`),
  KEY `idx_replenishment_rules_source` (`tenant_id`,`warehouse_id`,`source_location_id`),
  KEY `fk_replenishment_rules_warehouse` (`warehouse_id`,`tenant_id`),
  KEY `fk_replenishment_rules_item` (`item_id`,`tenant_id`),
  KEY `fk_replenishment_rules_source` (`source_location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_replenishment_rules_destination` (`destination_location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_replenishment_rules_created_by` (`created_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_replenishment_rules_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_rules_destination` FOREIGN KEY (`destination_location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_rules_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_rules_source` FOREIGN KEY (`source_location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_rules_warehouse` FOREIGN KEY (`warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_replenishment_rules_different_location` CHECK ((`source_location_id` <> `destination_location_id`)),
  CONSTRAINT `chk_replenishment_rules_maximum` CHECK (((`maximum_replenishment_qty` is null) or (`maximum_replenishment_qty` > 0))),
  CONSTRAINT `chk_replenishment_rules_minimum` CHECK ((`minimum_qty` >= 0)),
  CONSTRAINT `chk_replenishment_rules_priority` CHECK ((`priority` in (_utf8mb4'LOW',_utf8mb4'NORMAL',_utf8mb4'HIGH',_utf8mb4'URGENT'))),
  CONSTRAINT `chk_replenishment_rules_target` CHECK ((`replenish_to_qty` > `minimum_qty`)),
  CONSTRAINT `chk_replenishment_rules_type` CHECK ((`rule_type` in (_utf8mb4'MIN_MAX',_utf8mb4'MANUAL')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `replenishment_task_line_serials`
--

DROP TABLE IF EXISTS `replenishment_task_line_serials`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `replenishment_task_line_serials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `replenishment_task_line_id` bigint NOT NULL,
  `replenishment_task_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `source_location_id` bigint NOT NULL,
  `destination_location_id` bigint NOT NULL,
  `serial_id` bigint NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ALLOCATED',
  `moved_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_replenishment_task_line_serials_serial` (`tenant_id`,`replenishment_task_line_id`,`serial_id`),
  KEY `idx_replenishment_task_line_serials_serial` (`tenant_id`,`serial_id`,`status`),
  KEY `idx_replenishment_task_line_serials_task` (`tenant_id`,`replenishment_task_id`,`status`),
  KEY `fk_replenishment_task_line_serials_line` (`replenishment_task_line_id`,`tenant_id`,`replenishment_task_id`,`warehouse_id`,`item_id`,`source_location_id`,`destination_location_id`),
  KEY `fk_replenishment_task_line_serials_serial` (`serial_id`,`tenant_id`,`item_id`),
  CONSTRAINT `fk_replenishment_task_line_serials_line` FOREIGN KEY (`replenishment_task_line_id`, `tenant_id`, `replenishment_task_id`, `warehouse_id`, `item_id`, `source_location_id`, `destination_location_id`) REFERENCES `replenishment_task_lines` (`id`, `tenant_id`, `replenishment_task_id`, `warehouse_id`, `item_id`, `source_location_id`, `destination_location_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_task_line_serials_serial` FOREIGN KEY (`serial_id`, `tenant_id`, `item_id`) REFERENCES `inventory_serials` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_replenishment_task_line_serials_status` CHECK ((`status` in (_utf8mb4'ALLOCATED',_utf8mb4'MOVED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `replenishment_task_lines`
--

DROP TABLE IF EXISTS `replenishment_task_lines`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `replenishment_task_lines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `replenishment_task_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `source_location_id` bigint NOT NULL,
  `destination_location_id` bigint NOT NULL,
  `lot_id` bigint DEFAULT NULL,
  `line_number` int NOT NULL,
  `planned_qty` decimal(19,4) NOT NULL,
  `confirmed_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `lot_key` bigint GENERATED ALWAYS AS (coalesce(`lot_id`,0)) STORED,
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_replenishment_task_lines_task_number` (`tenant_id`,`replenishment_task_id`,`line_number`),
  UNIQUE KEY `uk_replenishment_task_lines_id_scope` (`id`,`tenant_id`,`replenishment_task_id`,`warehouse_id`,`item_id`,`source_location_id`,`destination_location_id`),
  UNIQUE KEY `uk_replenishment_task_lines_bucket` (`tenant_id`,`replenishment_task_id`,`item_id`,`source_location_id`,`destination_location_id`,`lot_key`),
  KEY `idx_replenishment_task_lines_item` (`tenant_id`,`item_id`,`status`),
  KEY `idx_replenishment_task_lines_source` (`tenant_id`,`warehouse_id`,`source_location_id`,`status`),
  KEY `idx_replenishment_task_lines_destination` (`tenant_id`,`warehouse_id`,`destination_location_id`,`status`),
  KEY `idx_replenishment_task_lines_lot` (`tenant_id`,`lot_id`),
  KEY `fk_replenishment_task_lines_task` (`replenishment_task_id`,`tenant_id`,`warehouse_id`),
  KEY `fk_replenishment_task_lines_item` (`item_id`,`tenant_id`),
  KEY `fk_replenishment_task_lines_source` (`source_location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_replenishment_task_lines_destination` (`destination_location_id`,`warehouse_id`,`tenant_id`),
  KEY `fk_replenishment_task_lines_lot` (`lot_id`,`tenant_id`,`item_id`),
  CONSTRAINT `fk_replenishment_task_lines_destination` FOREIGN KEY (`destination_location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_task_lines_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_task_lines_lot` FOREIGN KEY (`lot_id`, `tenant_id`, `item_id`) REFERENCES `inventory_lots` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_task_lines_source` FOREIGN KEY (`source_location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_task_lines_task` FOREIGN KEY (`replenishment_task_id`, `tenant_id`, `warehouse_id`) REFERENCES `replenishment_tasks` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_replenishment_task_lines_confirmed_qty` CHECK (((`confirmed_qty` >= 0) and (`confirmed_qty` <= `planned_qty`))),
  CONSTRAINT `chk_replenishment_task_lines_different_location` CHECK ((`source_location_id` <> `destination_location_id`)),
  CONSTRAINT `chk_replenishment_task_lines_planned_qty` CHECK ((`planned_qty` > 0)),
  CONSTRAINT `chk_replenishment_task_lines_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'IN_PROGRESS',_utf8mb4'PARTIALLY_COMPLETED',_utf8mb4'COMPLETED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `replenishment_tasks`
--

DROP TABLE IF EXISTS `replenishment_tasks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `replenishment_tasks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `replenishment_rule_id` bigint DEFAULT NULL,
  `task_number` varchar(60) NOT NULL,
  `task_type` varchar(20) NOT NULL DEFAULT 'MANUAL',
  `status` varchar(30) NOT NULL DEFAULT 'DRAFT',
  `priority` varchar(20) NOT NULL DEFAULT 'NORMAL',
  `assigned_to_membership_id` bigint DEFAULT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `planned_at` datetime(6) DEFAULT NULL,
  `released_at` datetime(6) DEFAULT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `cancelled_at` datetime(6) DEFAULT NULL,
  `notes` text,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_replenishment_tasks_public_id` (`public_id`),
  UNIQUE KEY `uk_replenishment_tasks_number` (`tenant_id`,`task_number`),
  UNIQUE KEY `uk_replenishment_tasks_id_scope` (`id`,`tenant_id`,`warehouse_id`),
  KEY `idx_replenishment_tasks_warehouse_status` (`tenant_id`,`warehouse_id`,`status`),
  KEY `idx_replenishment_tasks_assigned_to` (`tenant_id`,`assigned_to_membership_id`,`status`),
  KEY `idx_replenishment_tasks_rule` (`tenant_id`,`replenishment_rule_id`),
  KEY `fk_replenishment_tasks_warehouse` (`warehouse_id`,`tenant_id`),
  KEY `fk_replenishment_tasks_rule` (`replenishment_rule_id`,`tenant_id`,`warehouse_id`),
  KEY `fk_replenishment_tasks_assigned_to` (`assigned_to_membership_id`,`tenant_id`),
  KEY `fk_replenishment_tasks_created_by` (`created_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_replenishment_tasks_assigned_to` FOREIGN KEY (`assigned_to_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_tasks_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_tasks_rule` FOREIGN KEY (`replenishment_rule_id`, `tenant_id`, `warehouse_id`) REFERENCES `replenishment_rules` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_replenishment_tasks_warehouse` FOREIGN KEY (`warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_replenishment_tasks_priority` CHECK ((`priority` in (_utf8mb4'LOW',_utf8mb4'NORMAL',_utf8mb4'HIGH',_utf8mb4'URGENT'))),
  CONSTRAINT `chk_replenishment_tasks_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'READY',_utf8mb4'ASSIGNED',_utf8mb4'IN_PROGRESS',_utf8mb4'COMPLETED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_replenishment_tasks_type` CHECK ((`task_type` in (_utf8mb4'MANUAL',_utf8mb4'AUTOMATIC')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `role_permissions`
--

DROP TABLE IF EXISTS `role_permissions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `role_permissions` (
  `role_id` bigint NOT NULL,
  `permission_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`role_id`,`permission_id`),
  KEY `idx_role_permissions_permission` (`permission_id`),
  CONSTRAINT `fk_role_permissions_permission` FOREIGN KEY (`permission_id`) REFERENCES `permissions` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_role_permissions_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roles`
--

DROP TABLE IF EXISTS `roles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roles` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `role_code` varchar(80) NOT NULL,
  `role_name` varchar(120) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `is_system_role` tinyint(1) NOT NULL DEFAULT '0',
  `status` varchar(30) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_roles_public_id` (`public_id`),
  UNIQUE KEY `uk_roles_tenant_code` (`tenant_id`,`role_code`),
  UNIQUE KEY `uk_roles_id_tenant` (`id`,`tenant_id`),
  KEY `idx_roles_status` (`tenant_id`,`status`),
  CONSTRAINT `fk_roles_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_roles_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `stock_count_adjustment_links`
--

DROP TABLE IF EXISTS `stock_count_adjustment_links`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_count_adjustment_links` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `stock_count_id` bigint NOT NULL,
  `inventory_adjustment_id` bigint NOT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_count_adjustment_links_pair` (`tenant_id`,`stock_count_id`,`inventory_adjustment_id`),
  KEY `idx_stock_count_adjustment_links_adjustment` (`tenant_id`,`inventory_adjustment_id`),
  KEY `fk_stock_count_adjustment_links_count` (`stock_count_id`,`tenant_id`,`warehouse_id`),
  KEY `fk_stock_count_adjustment_links_adjustment` (`inventory_adjustment_id`,`tenant_id`,`warehouse_id`),
  KEY `fk_stock_count_adjustment_links_created_by` (`created_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_stock_count_adjustment_links_adjustment` FOREIGN KEY (`inventory_adjustment_id`, `tenant_id`, `warehouse_id`) REFERENCES `inventory_adjustments` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_count_adjustment_links_count` FOREIGN KEY (`stock_count_id`, `tenant_id`, `warehouse_id`) REFERENCES `stock_counts` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_count_adjustment_links_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `stock_count_lines`
--

DROP TABLE IF EXISTS `stock_count_lines`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_count_lines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `stock_count_scope_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `location_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `lot_id` bigint DEFAULT NULL,
  `lot_key` bigint GENERATED ALWAYS AS (coalesce(`lot_id`,0)) STORED,
  `stock_status` varchar(30) NOT NULL DEFAULT 'AVAILABLE',
  `expected_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `line_status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_count_lines_public_id` (`public_id`),
  UNIQUE KEY `uk_stock_count_lines_id_scope` (`id`,`tenant_id`,`item_id`,`warehouse_id`),
  UNIQUE KEY `uk_stock_count_lines_bucket` (`tenant_id`,`stock_count_scope_id`,`item_id`,`location_id`,`lot_key`,`stock_status`),
  KEY `idx_stock_count_lines_item` (`tenant_id`,`item_id`),
  KEY `idx_stock_count_lines_status` (`tenant_id`,`line_status`),
  KEY `fk_stock_count_lines_scope` (`stock_count_scope_id`,`tenant_id`,`warehouse_id`,`location_id`),
  KEY `fk_stock_count_lines_item` (`item_id`,`tenant_id`),
  KEY `fk_stock_count_lines_lot` (`lot_id`,`tenant_id`,`item_id`),
  CONSTRAINT `fk_stock_count_lines_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_count_lines_lot` FOREIGN KEY (`lot_id`, `tenant_id`, `item_id`) REFERENCES `inventory_lots` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_count_lines_scope` FOREIGN KEY (`stock_count_scope_id`, `tenant_id`, `warehouse_id`, `location_id`) REFERENCES `stock_count_scopes` (`id`, `tenant_id`, `warehouse_id`, `location_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_stock_count_lines_expected_qty` CHECK ((`expected_qty` >= 0)),
  CONSTRAINT `chk_stock_count_lines_status` CHECK ((`line_status` in (_utf8mb4'PENDING',_utf8mb4'COUNTED',_utf8mb4'RECOUNT_REQUIRED',_utf8mb4'REVIEWED',_utf8mb4'APPROVED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_stock_count_lines_stock_status` CHECK ((`stock_status` in (_utf8mb4'AVAILABLE',_utf8mb4'QUARANTINED',_utf8mb4'DAMAGED',_utf8mb4'EXPIRED',_utf8mb4'BLOCKED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `stock_count_results`
--

DROP TABLE IF EXISTS `stock_count_results`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_count_results` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `stock_count_line_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `count_round` int NOT NULL,
  `counted_qty` decimal(19,4) NOT NULL,
  `counted_by_membership_id` bigint NOT NULL,
  `counted_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `result_status` varchar(20) NOT NULL DEFAULT 'SUBMITTED',
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_count_results_line_round` (`tenant_id`,`stock_count_line_id`,`count_round`),
  KEY `idx_stock_count_results_counter` (`tenant_id`,`counted_by_membership_id`),
  KEY `fk_stock_count_results_line` (`stock_count_line_id`,`tenant_id`,`item_id`,`warehouse_id`),
  KEY `fk_stock_count_results_counter` (`counted_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_stock_count_results_counter` FOREIGN KEY (`counted_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_count_results_line` FOREIGN KEY (`stock_count_line_id`, `tenant_id`, `item_id`, `warehouse_id`) REFERENCES `stock_count_lines` (`id`, `tenant_id`, `item_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_stock_count_results_qty` CHECK ((`counted_qty` >= 0)),
  CONSTRAINT `chk_stock_count_results_round` CHECK ((`count_round` > 0)),
  CONSTRAINT `chk_stock_count_results_status` CHECK ((`result_status` in (_utf8mb4'SUBMITTED',_utf8mb4'VOIDED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `stock_count_scopes`
--

DROP TABLE IF EXISTS `stock_count_scopes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_count_scopes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `stock_count_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `location_id` bigint NOT NULL,
  `scope_status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `started_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_count_scopes_count_location` (`tenant_id`,`stock_count_id`,`location_id`),
  UNIQUE KEY `uk_stock_count_scopes_id_scope` (`id`,`tenant_id`,`warehouse_id`,`location_id`),
  KEY `fk_stock_count_scopes_header` (`stock_count_id`,`tenant_id`,`warehouse_id`),
  KEY `fk_stock_count_scopes_location` (`location_id`,`warehouse_id`,`tenant_id`),
  CONSTRAINT `fk_stock_count_scopes_header` FOREIGN KEY (`stock_count_id`, `tenant_id`, `warehouse_id`) REFERENCES `stock_counts` (`id`, `tenant_id`, `warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_count_scopes_location` FOREIGN KEY (`location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_stock_count_scopes_status` CHECK ((`scope_status` in (_utf8mb4'PENDING',_utf8mb4'IN_PROGRESS',_utf8mb4'COMPLETED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `stock_counts`
--

DROP TABLE IF EXISTS `stock_counts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_counts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `document_number` varchar(60) NOT NULL,
  `count_type` varchar(30) NOT NULL DEFAULT 'CYCLE_COUNT',
  `count_mode` varchar(20) NOT NULL DEFAULT 'BLIND',
  `status` varchar(30) NOT NULL DEFAULT 'DRAFT',
  `freeze_stock` tinyint(1) NOT NULL DEFAULT '0',
  `scheduled_at` datetime(6) DEFAULT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `submitted_at` datetime(6) DEFAULT NULL,
  `reviewed_at` datetime(6) DEFAULT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `reviewed_by_membership_id` bigint DEFAULT NULL,
  `approved_by_membership_id` bigint DEFAULT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_counts_public_id` (`public_id`),
  UNIQUE KEY `uk_stock_counts_tenant_document` (`tenant_id`,`document_number`),
  UNIQUE KEY `uk_stock_counts_id_tenant_warehouse` (`id`,`tenant_id`,`warehouse_id`),
  KEY `idx_stock_counts_warehouse_status` (`tenant_id`,`warehouse_id`,`status`),
  KEY `idx_stock_counts_scheduled` (`tenant_id`,`scheduled_at`),
  KEY `fk_stock_counts_warehouse` (`warehouse_id`,`tenant_id`),
  KEY `fk_stock_counts_created_by` (`created_by_membership_id`,`tenant_id`),
  KEY `fk_stock_counts_reviewed_by` (`reviewed_by_membership_id`,`tenant_id`),
  KEY `fk_stock_counts_approved_by` (`approved_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_stock_counts_approved_by` FOREIGN KEY (`approved_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_counts_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_counts_reviewed_by` FOREIGN KEY (`reviewed_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_counts_warehouse` FOREIGN KEY (`warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_stock_counts_mode` CHECK ((`count_mode` in (_utf8mb4'BLIND',_utf8mb4'OPEN'))),
  CONSTRAINT `chk_stock_counts_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'PLANNED',_utf8mb4'IN_PROGRESS',_utf8mb4'SUBMITTED',_utf8mb4'UNDER_REVIEW',_utf8mb4'APPROVED',_utf8mb4'COMPLETED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_stock_counts_type` CHECK ((`count_type` in (_utf8mb4'FULL_COUNT',_utf8mb4'CYCLE_COUNT',_utf8mb4'SPOT_COUNT')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `stock_transfer_allocations`
--

DROP TABLE IF EXISTS `stock_transfer_allocations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_transfer_allocations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `stock_transfer_line_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `source_warehouse_id` bigint NOT NULL,
  `source_location_id` bigint NOT NULL,
  `destination_warehouse_id` bigint NOT NULL,
  `destination_location_id` bigint NOT NULL,
  `lot_id` bigint DEFAULT NULL,
  `lot_key` bigint GENERATED ALWAYS AS (coalesce(`lot_id`,0)) STORED,
  `planned_qty` decimal(19,4) NOT NULL,
  `dispatched_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `received_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `damaged_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `status` varchar(30) NOT NULL DEFAULT 'PLANNED',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_transfer_allocations_id_tenant_item` (`id`,`tenant_id`,`item_id`),
  UNIQUE KEY `uk_stock_transfer_allocations_bucket` (`tenant_id`,`stock_transfer_line_id`,`source_location_id`,`destination_location_id`,`lot_key`),
  KEY `idx_stock_transfer_allocations_source` (`tenant_id`,`source_warehouse_id`,`source_location_id`),
  KEY `idx_stock_transfer_allocations_destination` (`tenant_id`,`destination_warehouse_id`,`destination_location_id`),
  KEY `idx_stock_transfer_allocations_lot` (`tenant_id`,`lot_id`),
  KEY `fk_stock_transfer_allocations_source` (`source_location_id`,`source_warehouse_id`,`tenant_id`),
  KEY `fk_stock_transfer_allocations_destination` (`destination_location_id`,`destination_warehouse_id`,`tenant_id`),
  KEY `fk_stock_transfer_allocations_lot` (`lot_id`,`tenant_id`,`item_id`),
  KEY `fk_stock_transfer_allocations_line` (`stock_transfer_line_id`,`tenant_id`,`item_id`,`source_warehouse_id`,`destination_warehouse_id`),
  CONSTRAINT `fk_stock_transfer_allocations_destination` FOREIGN KEY (`destination_location_id`, `destination_warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_transfer_allocations_line` FOREIGN KEY (`stock_transfer_line_id`, `tenant_id`, `item_id`, `source_warehouse_id`, `destination_warehouse_id`) REFERENCES `stock_transfer_lines` (`id`, `tenant_id`, `item_id`, `source_warehouse_id`, `destination_warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_transfer_allocations_lot` FOREIGN KEY (`lot_id`, `tenant_id`, `item_id`) REFERENCES `inventory_lots` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_transfer_allocations_source` FOREIGN KEY (`source_location_id`, `source_warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_stock_transfer_allocations_locations` CHECK (((`source_location_id` <> `destination_location_id`) or (`source_warehouse_id` <> `destination_warehouse_id`))),
  CONSTRAINT `chk_stock_transfer_allocations_qty` CHECK (((`planned_qty` > 0) and (`dispatched_qty` >= 0) and (`received_qty` >= 0) and (`damaged_qty` >= 0) and ((`received_qty` + `damaged_qty`) <= `dispatched_qty`) and (`dispatched_qty` <= `planned_qty`))),
  CONSTRAINT `chk_stock_transfer_allocations_status` CHECK ((`status` in (_utf8mb4'PLANNED',_utf8mb4'DISPATCHED',_utf8mb4'IN_TRANSIT',_utf8mb4'PARTIALLY_RECEIVED',_utf8mb4'RECEIVED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `stock_transfer_line_serials`
--

DROP TABLE IF EXISTS `stock_transfer_line_serials`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_transfer_line_serials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `stock_transfer_allocation_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `serial_id` bigint NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PLANNED',
  `dispatched_at` datetime(6) DEFAULT NULL,
  `received_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_transfer_line_serials_allocation` (`tenant_id`,`stock_transfer_allocation_id`,`serial_id`),
  KEY `idx_stock_transfer_line_serials_serial` (`tenant_id`,`serial_id`),
  KEY `fk_stock_transfer_line_serials_allocation` (`stock_transfer_allocation_id`,`tenant_id`,`item_id`),
  KEY `fk_stock_transfer_line_serials_serial` (`serial_id`,`tenant_id`,`item_id`),
  CONSTRAINT `fk_stock_transfer_line_serials_allocation` FOREIGN KEY (`stock_transfer_allocation_id`, `tenant_id`, `item_id`) REFERENCES `stock_transfer_allocations` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_transfer_line_serials_serial` FOREIGN KEY (`serial_id`, `tenant_id`, `item_id`) REFERENCES `inventory_serials` (`id`, `tenant_id`, `item_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_stock_transfer_line_serials_status` CHECK ((`status` in (_utf8mb4'PLANNED',_utf8mb4'DISPATCHED',_utf8mb4'IN_TRANSIT',_utf8mb4'RECEIVED',_utf8mb4'DAMAGED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `stock_transfer_lines`
--

DROP TABLE IF EXISTS `stock_transfer_lines`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_transfer_lines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `stock_transfer_id` bigint NOT NULL,
  `source_warehouse_id` bigint NOT NULL,
  `destination_warehouse_id` bigint NOT NULL,
  `line_number` int NOT NULL,
  `item_id` bigint NOT NULL,
  `uom_id` bigint NOT NULL,
  `requested_qty` decimal(19,4) NOT NULL,
  `dispatched_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `received_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `damaged_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `short_qty` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_transfer_lines_public_id` (`public_id`),
  UNIQUE KEY `uk_stock_transfer_lines_number` (`tenant_id`,`stock_transfer_id`,`line_number`),
  UNIQUE KEY `uk_stock_transfer_lines_id_tenant_item` (`id`,`tenant_id`,`item_id`),
  UNIQUE KEY `uk_stock_transfer_lines_transfer_scope` (`id`,`tenant_id`,`item_id`,`source_warehouse_id`,`destination_warehouse_id`),
  KEY `idx_stock_transfer_lines_item` (`tenant_id`,`item_id`),
  KEY `fk_stock_transfer_lines_header` (`stock_transfer_id`,`tenant_id`,`source_warehouse_id`,`destination_warehouse_id`),
  KEY `fk_stock_transfer_lines_item` (`item_id`,`tenant_id`),
  KEY `fk_stock_transfer_lines_uom` (`uom_id`),
  CONSTRAINT `fk_stock_transfer_lines_header` FOREIGN KEY (`stock_transfer_id`, `tenant_id`, `source_warehouse_id`, `destination_warehouse_id`) REFERENCES `stock_transfers` (`id`, `tenant_id`, `source_warehouse_id`, `destination_warehouse_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_transfer_lines_item` FOREIGN KEY (`item_id`, `tenant_id`) REFERENCES `items` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_transfer_lines_uom` FOREIGN KEY (`uom_id`) REFERENCES `units_of_measure` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_stock_transfer_lines_number` CHECK ((`line_number` > 0)),
  CONSTRAINT `chk_stock_transfer_lines_quantities` CHECK (((`requested_qty` > 0) and (`dispatched_qty` >= 0) and (`received_qty` >= 0) and (`damaged_qty` >= 0) and (`short_qty` >= 0) and ((`received_qty` + `damaged_qty`) <= `dispatched_qty`) and (`dispatched_qty` <= `requested_qty`))),
  CONSTRAINT `chk_stock_transfer_lines_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'READY',_utf8mb4'DISPATCHED',_utf8mb4'PARTIALLY_RECEIVED',_utf8mb4'RECEIVED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `stock_transfers`
--

DROP TABLE IF EXISTS `stock_transfers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_transfers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `source_warehouse_id` bigint NOT NULL,
  `destination_warehouse_id` bigint NOT NULL,
  `document_number` varchar(60) NOT NULL,
  `external_reference` varchar(100) DEFAULT NULL,
  `transfer_type` varchar(30) NOT NULL DEFAULT 'STANDARD',
  `status` varchar(30) NOT NULL DEFAULT 'DRAFT',
  `requested_at` datetime(6) DEFAULT NULL,
  `scheduled_at` datetime(6) DEFAULT NULL,
  `dispatched_at` datetime(6) DEFAULT NULL,
  `received_at` datetime(6) DEFAULT NULL,
  `posted_at` datetime(6) DEFAULT NULL,
  `created_by_membership_id` bigint NOT NULL,
  `dispatched_by_membership_id` bigint DEFAULT NULL,
  `received_by_membership_id` bigint DEFAULT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_transfers_public_id` (`public_id`),
  UNIQUE KEY `uk_stock_transfers_tenant_document` (`tenant_id`,`document_number`),
  UNIQUE KEY `uk_stock_transfers_id_tenant_warehouses` (`id`,`tenant_id`,`source_warehouse_id`,`destination_warehouse_id`),
  KEY `idx_stock_transfers_source_status` (`tenant_id`,`source_warehouse_id`,`status`),
  KEY `idx_stock_transfers_destination_status` (`tenant_id`,`destination_warehouse_id`,`status`),
  KEY `idx_stock_transfers_scheduled` (`tenant_id`,`scheduled_at`),
  KEY `fk_stock_transfers_source_warehouse` (`source_warehouse_id`,`tenant_id`),
  KEY `fk_stock_transfers_destination_warehouse` (`destination_warehouse_id`,`tenant_id`),
  KEY `fk_stock_transfers_created_by` (`created_by_membership_id`,`tenant_id`),
  KEY `fk_stock_transfers_dispatched_by` (`dispatched_by_membership_id`,`tenant_id`),
  KEY `fk_stock_transfers_received_by` (`received_by_membership_id`,`tenant_id`),
  CONSTRAINT `fk_stock_transfers_created_by` FOREIGN KEY (`created_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_transfers_destination_warehouse` FOREIGN KEY (`destination_warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_transfers_dispatched_by` FOREIGN KEY (`dispatched_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_transfers_received_by` FOREIGN KEY (`received_by_membership_id`, `tenant_id`) REFERENCES `tenant_memberships` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_stock_transfers_source_warehouse` FOREIGN KEY (`source_warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_stock_transfers_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'RELEASED',_utf8mb4'IN_TRANSIT',_utf8mb4'PARTIALLY_RECEIVED',_utf8mb4'RECEIVED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_stock_transfers_type` CHECK ((`transfer_type` in (_utf8mb4'STANDARD',_utf8mb4'REPLENISHMENT',_utf8mb4'RETURN',_utf8mb4'OTHER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `tenant_memberships`
--

DROP TABLE IF EXISTS `tenant_memberships`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tenant_memberships` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'ACTIVE',
  `joined_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memberships_public_id` (`public_id`),
  UNIQUE KEY `uk_membership_tenant_user` (`tenant_id`,`user_id`),
  UNIQUE KEY `uk_membership_id_tenant` (`id`,`tenant_id`),
  KEY `idx_memberships_user` (`user_id`),
  KEY `idx_memberships_status` (`tenant_id`,`status`),
  CONSTRAINT `fk_memberships_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_memberships_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_memberships_status` CHECK ((`status` in (_utf8mb4'INVITED',_utf8mb4'ACTIVE',_utf8mb4'SUSPENDED',_utf8mb4'REMOVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `tenants`
--

DROP TABLE IF EXISTS `tenants`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tenants` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_code` varchar(64) NOT NULL,
  `legal_name` varchar(200) NOT NULL,
  `display_name` varchar(150) NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'ACTIVE',
  `timezone` varchar(60) NOT NULL DEFAULT 'Asia/Jakarta',
  `default_currency` char(3) NOT NULL DEFAULT 'IDR',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenants_public_id` (`public_id`),
  UNIQUE KEY `uk_tenants_code` (`tenant_code`),
  KEY `idx_tenants_status` (`status`),
  CONSTRAINT `chk_tenants_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'SUSPENDED',_utf8mb4'CLOSED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `units_of_measure`
--

DROP TABLE IF EXISTS `units_of_measure`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `units_of_measure` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uom_code` varchar(20) NOT NULL,
  `uom_name` varchar(100) NOT NULL,
  `dimension` varchar(30) NOT NULL,
  `decimal_places` tinyint unsigned NOT NULL DEFAULT '0',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uom_code` (`uom_code`),
  KEY `idx_uom_dimension` (`dimension`,`is_active`),
  CONSTRAINT `chk_uom_decimal_places` CHECK ((`decimal_places` <= 6)),
  CONSTRAINT `chk_uom_dimension` CHECK ((`dimension` in (_utf8mb4'COUNT',_utf8mb4'MASS',_utf8mb4'LENGTH',_utf8mb4'AREA',_utf8mb4'VOLUME',_utf8mb4'TIME')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `email` varchar(254) NOT NULL,
  `username` varchar(100) DEFAULT NULL,
  `password_hash` varchar(255) NOT NULL,
  `full_name` varchar(150) NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'ACTIVE',
  `email_verified_at` datetime(6) DEFAULT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_public_id` (`public_id`),
  UNIQUE KEY `uk_users_email` (`email`),
  UNIQUE KEY `uk_users_username` (`username`),
  KEY `idx_users_status` (`status`),
  CONSTRAINT `chk_users_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'PENDING_VERIFICATION',_utf8mb4'SUSPENDED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `warehouse_locations`
--

DROP TABLE IF EXISTS `warehouse_locations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `warehouse_locations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `parent_location_id` bigint DEFAULT NULL,
  `location_code` varchar(100) NOT NULL,
  `location_name` varchar(150) NOT NULL,
  `location_type` varchar(30) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `max_weight_capacity` decimal(19,4) DEFAULT NULL,
  `weight_uom` varchar(10) DEFAULT NULL,
  `max_volume_capacity` decimal(19,4) DEFAULT NULL,
  `volume_uom` varchar(10) DEFAULT NULL,
  `is_pickable` tinyint(1) NOT NULL DEFAULT '0',
  `is_receivable` tinyint(1) NOT NULL DEFAULT '0',
  `allow_mixed_skus` tinyint(1) NOT NULL DEFAULT '0',
  `allow_mixed_lots` tinyint(1) NOT NULL DEFAULT '0',
  `picking_sequence` int NOT NULL DEFAULT '0',
  `putaway_sequence` int NOT NULL DEFAULT '0',
  `status` varchar(30) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_locations_public_id` (`public_id`),
  UNIQUE KEY `uk_locations_warehouse_code` (`warehouse_id`,`location_code`),
  UNIQUE KEY `uk_locations_id_warehouse_tenant` (`id`,`warehouse_id`,`tenant_id`),
  KEY `idx_locations_tenant_warehouse` (`tenant_id`,`warehouse_id`),
  KEY `idx_locations_parent` (`parent_location_id`,`warehouse_id`),
  KEY `idx_locations_type_status` (`warehouse_id`,`location_type`,`status`),
  KEY `idx_locations_picking` (`warehouse_id`,`is_pickable`,`picking_sequence`),
  KEY `idx_locations_putaway` (`warehouse_id`,`putaway_sequence`),
  KEY `fk_locations_warehouse` (`warehouse_id`,`tenant_id`),
  KEY `fk_locations_parent` (`parent_location_id`,`warehouse_id`,`tenant_id`),
  CONSTRAINT `fk_locations_parent` FOREIGN KEY (`parent_location_id`, `warehouse_id`, `tenant_id`) REFERENCES `warehouse_locations` (`id`, `warehouse_id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_locations_warehouse` FOREIGN KEY (`warehouse_id`, `tenant_id`) REFERENCES `warehouses` (`id`, `tenant_id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_locations_sequences` CHECK (((`picking_sequence` >= 0) and (`putaway_sequence` >= 0))),
  CONSTRAINT `chk_locations_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE',_utf8mb4'BLOCKED',_utf8mb4'MAINTENANCE'))),
  CONSTRAINT `chk_locations_type` CHECK ((`location_type` in (_utf8mb4'ZONE',_utf8mb4'RACK',_utf8mb4'SHELF',_utf8mb4'BIN',_utf8mb4'RECEIVING',_utf8mb4'STORAGE',_utf8mb4'PICKING',_utf8mb4'PACKING',_utf8mb4'SHIPPING',_utf8mb4'STAGING',_utf8mb4'QUARANTINE',_utf8mb4'RETURNS',_utf8mb4'DAMAGE',_utf8mb4'OTHER'))),
  CONSTRAINT `chk_locations_volume_capacity` CHECK (((`max_volume_capacity` is null) or (`max_volume_capacity` >= 0))),
  CONSTRAINT `chk_locations_volume_uom` CHECK ((((`max_volume_capacity` is null) and (`volume_uom` is null)) or ((`max_volume_capacity` is not null) and (`volume_uom` is not null)))),
  CONSTRAINT `chk_locations_weight_capacity` CHECK (((`max_weight_capacity` is null) or (`max_weight_capacity` >= 0))),
  CONSTRAINT `chk_locations_weight_uom` CHECK ((((`max_weight_capacity` is null) and (`weight_uom` is null)) or ((`max_weight_capacity` is not null) and (`weight_uom` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `warehouses`
--

DROP TABLE IF EXISTS `warehouses`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `warehouses` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` char(36) NOT NULL,
  `tenant_id` bigint NOT NULL,
  `warehouse_code` varchar(64) NOT NULL,
  `warehouse_name` varchar(150) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `address_line` varchar(255) DEFAULT NULL,
  `city` varchar(100) DEFAULT NULL,
  `province` varchar(100) DEFAULT NULL,
  `postal_code` varchar(20) DEFAULT NULL,
  `country_code` char(2) NOT NULL DEFAULT 'ID',
  `contact_name` varchar(120) DEFAULT NULL,
  `contact_phone` varchar(30) DEFAULT NULL,
  `contact_email` varchar(254) DEFAULT NULL,
  `timezone` varchar(60) NOT NULL DEFAULT 'Asia/Jakarta',
  `status` varchar(30) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_warehouses_public_id` (`public_id`),
  UNIQUE KEY `uk_warehouses_tenant_code` (`tenant_id`,`warehouse_code`),
  UNIQUE KEY `uk_warehouses_id_tenant` (`id`,`tenant_id`),
  KEY `idx_warehouses_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `fk_warehouses_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `chk_warehouses_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE',_utf8mb4'MAINTENANCE',_utf8mb4'CLOSED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-11-15 19:25:04
