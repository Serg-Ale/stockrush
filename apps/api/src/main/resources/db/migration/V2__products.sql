create table products (
    id bigint generated always as identity primary key,
    name text not null unique,
    available_quantity integer not null check (available_quantity >= 0)
);
