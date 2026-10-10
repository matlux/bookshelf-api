ALTER TABLE author_book_counts ADD COLUMN refreshed_at TIMESTAMPTZ;
--;;
ALTER TABLE author_book_counts DROP CONSTRAINT author_book_counts_author_id_fkey;
--;;
ALTER TABLE author_book_counts ADD CONSTRAINT author_book_counts_author_id_fkey
  FOREIGN KEY (author_id) REFERENCES authors(id) ON DELETE CASCADE;
