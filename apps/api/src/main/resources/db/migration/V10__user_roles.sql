alter table users add column role text not null default 'CLIENTE'
    check (role in ('CLIENTE', 'ADMIN'));
