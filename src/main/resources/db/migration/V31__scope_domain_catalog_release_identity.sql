alter table domain_catalog_release
    drop constraint if exists domain_catalog_release_release_key_key;

create unique index if not exists uk_domain_catalog_release_scope_key
    on domain_catalog_release (
        coalesce(tenant_id, ''),
        coalesce(environment, ''),
        release_key
    );
