#!/bin/bash

cd `dirname $0`

gradle javadoc

cd docs
mdbook build
cd ..

mkdir -p docs_output
rm -rf docs_output/*

mkdir -p docs_output/module-api
cp -r icejar-module-api/build/docs/javadoc/* docs_output/module-api

mkdir -p docs_output/ice-generated
cp -r ice-generated/build/docs/javadoc/* docs_output/ice-generated

mkdir -p docs_output/book
cp -r docs/book/* docs_output/book
