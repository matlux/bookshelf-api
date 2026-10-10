CREATE TABLE authors (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name TEXT NOT NULL CHECK (length(trim(name)) > 0)
);
--;;
CREATE TABLE books (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 300),
  author_id BIGINT NOT NULL REFERENCES authors(id),
  published_year INTEGER NOT NULL CHECK (published_year BETWEEN 1 AND 9999)
);
--;;
CREATE INDEX books_author_id_idx ON books(author_id);
--;;
INSERT INTO authors (name) VALUES ('Ursula K. Le Guin'), ('Octavia E. Butler'), ('Jane Austen');
--;;
CREATE TABLE author_book_counts (
  author_id BIGINT PRIMARY KEY REFERENCES authors(id),
  book_count BIGINT NOT NULL CHECK (book_count >= 0)
);
--;;
INSERT INTO author_book_counts (author_id, book_count) SELECT id, 0 FROM authors;
