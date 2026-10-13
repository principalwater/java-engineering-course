INSERT INTO store_identity(id, source_id) SELECT 1, RANDOM_UUID() WHERE NOT EXISTS (SELECT 1 FROM store_identity WHERE id = 1);
