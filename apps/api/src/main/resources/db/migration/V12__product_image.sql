-- A path inside the app (served with the web build), never a URL: no scheme, no host, no "//", no "..".
alter table products
    add column image_url text
        check (image_url is null or image_url ~ '^/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\.(webp|avif|png|jpg|svg)$');

update products set image_url = '/img/products/mechanical-keyboard.webp' where name = 'Mechanical Keyboard';
update products set image_url = '/img/products/wireless-mouse.webp' where name = 'Wireless Mouse';
update products set image_url = '/img/products/usb-c-hub.webp' where name = 'USB-C Hub';
update products set image_url = '/img/products/noise-cancelling-headphones.webp' where name = 'Noise-Cancelling Headphones';
