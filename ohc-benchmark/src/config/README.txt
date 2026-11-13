OHC benchmark runtime configuration samples.

This directory is bundled into the ohc-benchmark distribution archive (see
src/assembly/assembly.xml) under config/ and is meant to hold user-supplied
runtime configuration (e.g. JVM options, allocator preload scripts, or saved
benchmark parameter sets) that can be mounted alongside the executable jar.

The benchmark itself is driven purely by command-line options -- see
"java -jar ohc-benchmark-<version>.jar -h" for the full list -- so no file
here is read automatically. Drop your own config files in this directory when
you need to ship a pre-tuned setup.
