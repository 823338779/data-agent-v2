-- V4 通过 ALTER TABLE 追加代理主键，导致这些表的 identity id 物理位置位于列尾。
-- PostgreSQL 不支持原地调整列顺序，因此本迁移在同一事务内重建目标表并完整复制已有数据。
create temporary table identity_first_target (
    table_name text primary key,
    migration_order integer not null unique
) on commit drop;

insert into identity_first_target(table_name, migration_order) values
    ('tenant', 1),
    ('user_account', 2),
    ('tenant_membership', 3),
    ('conversation_session', 4),
    ('conversation_message', 5),
    ('agent_execution', 6),
    ('agent_execution_event', 7),
    ('agent_execution_stream_checkpoint', 8),
    ('agent_execution_attempt', 9);

-- 在表替换前保存所有依赖对象；外键统一最后恢复，避免重建顺序影响跨表引用。
create temporary table identity_first_foreign_key (
    owner_table text not null,
    constraint_name text not null,
    definition text not null,
    primary key (owner_table, constraint_name)
) on commit drop;

insert into identity_first_foreign_key(owner_table, constraint_name, definition)
select owner.relname,
       constraint_item.conname,
       pg_get_constraintdef(constraint_item.oid, true)
  from pg_constraint constraint_item
  join pg_class owner on owner.oid = constraint_item.conrelid
  join pg_namespace owner_namespace on owner_namespace.oid = owner.relnamespace
  join pg_class referenced on referenced.oid = constraint_item.confrelid
 where constraint_item.contype = 'f'
   and owner_namespace.nspname = current_schema()
   and (
       owner.relname in (select table_name from identity_first_target)
       or referenced.relname in (select table_name from identity_first_target)
   );

create temporary table identity_first_constraint (
    owner_table text not null,
    constraint_name text not null,
    definition text not null,
    primary key (owner_table, constraint_name)
) on commit drop;

insert into identity_first_constraint(owner_table, constraint_name, definition)
select owner.relname,
       constraint_item.conname,
       pg_get_constraintdef(constraint_item.oid, true)
  from pg_constraint constraint_item
  join pg_class owner on owner.oid = constraint_item.conrelid
  join pg_namespace owner_namespace on owner_namespace.oid = owner.relnamespace
 where owner_namespace.nspname = current_schema()
   and owner.relname in (select table_name from identity_first_target)
   and constraint_item.contype <> 'f';

create temporary table identity_first_index (
    owner_table text not null,
    index_name text not null,
    definition text not null,
    primary key (owner_table, index_name)
) on commit drop;

insert into identity_first_index(owner_table, index_name, definition)
select owner.relname,
       index_relation.relname,
       pg_get_indexdef(index_relation.oid)
  from pg_index index_item
  join pg_class owner on owner.oid = index_item.indrelid
  join pg_namespace owner_namespace on owner_namespace.oid = owner.relnamespace
  join pg_class index_relation on index_relation.oid = index_item.indexrelid
 where owner_namespace.nspname = current_schema()
   and owner.relname in (select table_name from identity_first_target)
   and not exists (
       select 1
         from pg_constraint constraint_item
        where constraint_item.conindid = index_item.indexrelid
   );

create temporary table identity_first_comment (
    owner_table text not null,
    column_name text,
    comment_text text not null
) on commit drop;

insert into identity_first_comment(owner_table, column_name, comment_text)
select owner.relname, null, obj_description(owner.oid, 'pg_class')
  from pg_class owner
  join pg_namespace owner_namespace on owner_namespace.oid = owner.relnamespace
 where owner_namespace.nspname = current_schema()
   and owner.relname in (select table_name from identity_first_target)
   and obj_description(owner.oid, 'pg_class') is not null
union all
select owner.relname, attribute_item.attname, col_description(owner.oid, attribute_item.attnum)
  from pg_class owner
  join pg_namespace owner_namespace on owner_namespace.oid = owner.relnamespace
  join pg_attribute attribute_item on attribute_item.attrelid = owner.oid
 where owner_namespace.nspname = current_schema()
   and owner.relname in (select table_name from identity_first_target)
   and attribute_item.attnum > 0
   and not attribute_item.attisdropped
   and col_description(owner.oid, attribute_item.attnum) is not null;

-- 移除指向目标表或由目标表持有的外键，使后续表替换不遗留旧表 OID 依赖。
do $$
declare
    foreign_key_item record;
begin
    for foreign_key_item in
        select owner_table, constraint_name
          from identity_first_foreign_key
         order by owner_table, constraint_name
    loop
        execute format(
            'alter table %I.%I drop constraint %I',
            current_schema(),
            foreign_key_item.owner_table,
            foreign_key_item.constraint_name);
    end loop;
end $$;

-- 逐表生成等价列定义，并把 id 放到第一位；数据复制显式覆盖 identity 值以保持主键稳定。
do $$
declare
    target_item record;
    constraint_item record;
    index_item record;
    comment_item record;
    source_table text;
    column_definitions text;
    copied_columns text;
    identity_sequence text;
    maximum_id bigint;
begin
    for target_item in
        select table_name
          from identity_first_target
         order by migration_order
    loop
        source_table := '__identity_first_' || target_item.table_name;

        select string_agg(
                   format(
                       '%I %s%s%s',
                       attribute_item.attname,
                       format_type(attribute_item.atttypid, attribute_item.atttypmod),
                       case
                           when attribute_item.attidentity = 'a'
                               then ' generated always as identity'
                           when attribute_item.attidentity = 'd'
                               then ' generated by default as identity'
                           when attribute_item.attgenerated = 's'
                               then ' generated always as ('
                                   || pg_get_expr(default_item.adbin, default_item.adrelid)
                                   || ') stored'
                           when default_item.adbin is not null
                               then ' default '
                                   || pg_get_expr(default_item.adbin, default_item.adrelid)
                           else ''
                       end,
                       case when attribute_item.attnotnull then ' not null' else '' end),
                   ', ' order by
                       case when attribute_item.attname = 'id' then 0 else 1 end,
                       attribute_item.attnum)
          into column_definitions
          from pg_attribute attribute_item
          join pg_class owner on owner.oid = attribute_item.attrelid
          join pg_namespace owner_namespace on owner_namespace.oid = owner.relnamespace
          left join pg_attrdef default_item
            on default_item.adrelid = attribute_item.attrelid
           and default_item.adnum = attribute_item.attnum
         where owner_namespace.nspname = current_schema()
           and owner.relname = target_item.table_name
           and attribute_item.attnum > 0
           and not attribute_item.attisdropped;

        select string_agg(format('%I', attribute_item.attname), ', ' order by
                   case when attribute_item.attname = 'id' then 0 else 1 end,
                   attribute_item.attnum)
          into copied_columns
          from pg_attribute attribute_item
          join pg_class owner on owner.oid = attribute_item.attrelid
          join pg_namespace owner_namespace on owner_namespace.oid = owner.relnamespace
         where owner_namespace.nspname = current_schema()
           and owner.relname = target_item.table_name
           and attribute_item.attnum > 0
           and not attribute_item.attisdropped
           and attribute_item.attgenerated = '';

        for constraint_item in
            select constraint_name
              from identity_first_constraint
             where owner_table = target_item.table_name
             order by constraint_name
        loop
            execute format(
                'alter table %I.%I drop constraint %I',
                current_schema(),
                target_item.table_name,
                constraint_item.constraint_name);
        end loop;

        for index_item in
            select index_name
              from identity_first_index
             where owner_table = target_item.table_name
             order by index_name
        loop
            execute format('drop index %I.%I', current_schema(), index_item.index_name);
        end loop;

        execute format(
            'alter table %I.%I alter column id drop identity',
            current_schema(),
            target_item.table_name);
        execute format(
            'alter table %I.%I rename to %I',
            current_schema(),
            target_item.table_name,
            source_table);
        execute format(
            'create table %I.%I (%s)',
            current_schema(),
            target_item.table_name,
            column_definitions);
        execute format(
            'insert into %I.%I (%s) overriding system value select %s from %I.%I',
            current_schema(),
            target_item.table_name,
            copied_columns,
            copied_columns,
            current_schema(),
            source_table);
        execute format('drop table %I.%I', current_schema(), source_table);

        for constraint_item in
            select constraint_name, definition
              from identity_first_constraint
             where owner_table = target_item.table_name
             order by constraint_name
        loop
            execute format(
                'alter table %I.%I add constraint %I %s',
                current_schema(),
                target_item.table_name,
                constraint_item.constraint_name,
                constraint_item.definition);
        end loop;

        for index_item in
            select definition
              from identity_first_index
             where owner_table = target_item.table_name
             order by index_name
        loop
            execute index_item.definition;
        end loop;

        for comment_item in
            select column_name, comment_text
              from identity_first_comment
             where owner_table = target_item.table_name
             order by column_name nulls first
        loop
            if comment_item.column_name is null then
                execute format(
                    'comment on table %I.%I is %L',
                    current_schema(),
                    target_item.table_name,
                    comment_item.comment_text);
            else
                execute format(
                    'comment on column %I.%I.%I is %L',
                    current_schema(),
                    target_item.table_name,
                    comment_item.column_name,
                    comment_item.comment_text);
            end if;
        end loop;

        execute format(
            'select max(id) from %I.%I',
            current_schema(),
            target_item.table_name)
            into maximum_id;
        identity_sequence := pg_get_serial_sequence(
            format('%I.%I', current_schema(), target_item.table_name),
            'id');
        if maximum_id is null then
            perform setval(identity_sequence, 1, false);
        else
            perform setval(identity_sequence, maximum_id, true);
        end if;
    end loop;
end $$;

-- 所有目标表都已获得新 OID 后，恢复外键并重新绑定到新表。
do $$
declare
    foreign_key_item record;
begin
    for foreign_key_item in
        select owner_table, constraint_name, definition
          from identity_first_foreign_key
         order by owner_table, constraint_name
    loop
        execute format(
            'alter table %I.%I add constraint %I %s',
            current_schema(),
            foreign_key_item.owner_table,
            foreign_key_item.constraint_name,
            foreign_key_item.definition);
    end loop;
end $$;
