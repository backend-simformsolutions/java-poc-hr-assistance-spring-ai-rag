-- Database initialisation, run on every startup (spring.sql.init.mode=always).
-- Every statement is idempotent, so repeated runs are safe.
--
-- The vector_store table itself is created by Spring AI's PgVectorStore when
-- spring.ai.vectorstore.pgvector.initialize-schema=true, along with its IVFFlat index:
--
--   vector_store(id UUID, content TEXT, metadata JSON, embedding VECTOR(768))
--
-- The embedding width is fixed by the configured embedding model and must match
-- spring.ai.vectorstore.pgvector.dimensions.

-- Adds the vector column type and its distance operators (<=> cosine, <-> L2,
-- <#> negative inner product). Requires a role with CREATE EXTENSION privilege.
CREATE EXTENSION IF NOT EXISTS vector;

-- Supplies uuid_generate_v4(), used for vector_store primary keys.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";