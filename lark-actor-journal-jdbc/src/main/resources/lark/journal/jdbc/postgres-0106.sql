-- Spec 0106 on a journal created before it: the index a partitioned projection reads by. Apply it with the service's
-- own migrations; on a large journal, run it as `create index concurrently` outside a transaction instead.
create index lark_journal_kind_slice on lark_journal (kind, slice, ordering);
