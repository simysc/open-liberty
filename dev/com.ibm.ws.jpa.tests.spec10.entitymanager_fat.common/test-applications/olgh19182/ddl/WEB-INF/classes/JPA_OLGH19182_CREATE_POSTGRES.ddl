CREATE TABLE HelmetEntityOLGH19182 (id INTEGER NOT NULL, color VARCHAR(255), SHELF_ID BIGINT, PRIMARY KEY (id));
CREATE TABLE JPA10EntityManagerEntityA (id INTEGER NOT NULL, strData VARCHAR(255), PRIMARY KEY (id));
CREATE TABLE JPA10EntityManagerEntityA_JPA10EntityManagerEntityB (ENTITYALIST_ID INTEGER, ENTITYBLIST_ID INTEGER);
CREATE TABLE JPA10EntityManagerEntityB (id INTEGER NOT NULL, strData VARCHAR(255), PRIMARY KEY (id));
CREATE TABLE JPA10EntityManagerEntityC (id INTEGER NOT NULL, strData VARCHAR(255), ENTITYA_ID INTEGER, ENTITYALAZY_ID INTEGER, PRIMARY KEY (id));
CREATE TABLE JPA_HELMET_PROPERTIES (HELMET_ID INTEGER, PROPERTY_NAME VARCHAR(255) NOT NULL, PROPERTY_VALUE VARCHAR(255));
CREATE TABLE ShelfEntityOLGH19182 (id BIGINT NOT NULL, name VARCHAR(255), PRIMARY KEY (id));
CREATE INDEX I_HLMT182_SHELF ON HelmetEntityOLGH19182 (SHELF_ID);
CREATE INDEX I_JP10TYB_ELEMENT ON JPA10EntityManagerEntityA_JPA10EntityManagerEntityB (ENTITYBLIST_ID);
CREATE INDEX I_JP10TYB_ENTITYALIST_ID ON JPA10EntityManagerEntityA_JPA10EntityManagerEntityB (ENTITYALIST_ID);
CREATE INDEX I_JP10TYC_ENTITYA ON JPA10EntityManagerEntityC (ENTITYA_ID);
CREATE INDEX I_JP10TYC_ENTITYALAZY ON JPA10EntityManagerEntityC (ENTITYALAZY_ID);
CREATE INDEX I_JP_HRTS_HELMET_ID ON JPA_HELMET_PROPERTIES (HELMET_ID);