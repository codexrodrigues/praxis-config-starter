alter table domain_catalog_release
    add column if not exists resource_key varchar(255);

update domain_catalog_release
set resource_key = nullif(raw_payload ->> 'resourceKey', '')
where resource_key is null;

create index if not exists idx_domain_catalog_release_scope_resource_generated
    on domain_catalog_release (
        tenant_id,
        environment,
        service_key,
        resource_key,
        generated_at desc,
        created_at desc
    );
