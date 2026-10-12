CREATE TABLE IF NOT EXISTS movies (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    release_year INTEGER NOT NULL
);
INSERT INTO movies (id, name, type, release_year)
VALUES (1, 'Star Wars: Episode I - The Phantom Menace', 'Action', 1999)
ON CONFLICT (id) DO NOTHING;
