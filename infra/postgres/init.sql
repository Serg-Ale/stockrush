-- One database per service (ADR 0004), on a single server for the demo.
create user stockrush password 'stockrush';
create database stockrush owner stockrush;
create user payments password 'payments';
create database payments owner payments;
