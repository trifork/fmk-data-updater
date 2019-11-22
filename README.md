Er baseret på, at der parallelt med dette projekt er checket en fast version af fmk ud, projektnavn fmk-fixedversion. Dette for at sikre, at evt. opgraderinger af modul-klientversioner, 
hvor de tilhørende moduler endnu ikke er deploy'ed alle steder, forstyrrer bygget.

i Eclipse: Export as "Runnable JAR File"

initiel tar bygges på udvikler maskine: ant archive-test1
uploades til /pack/temp: 				scp fmk-data-updater-eclipse.tar chj@app01.test1.fmk.netic.dk:/pack/temp
udpakkes: 								tar -xf fmk-data-updater-eclipse.tar

test2
======
Upload properties til app01.test1.fmk.netic.dk:/pack/temp/test2
På test1: Pak tar fra /pack/temp/fmk-data-updater med test2-propertyfiler, gemmes i /pack/temp/test2/fmk-data-updater-eclipse.tar:
          tar -cf /pack/temp/stage/fmk-data-updater-eclipse.tar fmk-data-updater-eclipse_lib/* fmk-data-updater-eclipse.jar test2/*.properties *.js
På test2: scp chj@app01.test1.fmk.netic.dk:/pack/temp/test2/fmk-data-updater-eclipse.tar .
Udpak på test2: tar -xf fmk-data-updater-eclipse.tar
cd test2
java -cp "../fmk-data-updater-eclipse_lib:../fmk-data-updater-eclipse.jar" dk.medicinkortet.dataupdater.Main dosageenddate test

stage
======
Upload properties til app01.test1.fmk.netic.dk:/pack/temp/stage
På test1: Pak tar fra /pack/temp/fmk-data-updater med stage-propertyfiler, gemmes i /pack/temp/stage/fmk-data-updater-eclipse.tar:
          tar -cf /pack/temp/stage/fmk-data-updater-eclipse.tar fmk-data-updater-eclipse_lib/* fmk-data-updater-eclipse.jar stage/*.properties *.js
På stage: scp chj@app01.test1.fmk.netic.dk:/pack/temp/test2/fmk-data-updater-eclipse.tar .
Udpak på stage2: tar -xf fmk-data-updater-eclipse.tar
cd stage
java -cp "../fmk-data-updater-eclipse_lib:../fmk-data-updater-eclipse.jar" dk.medicinkortet.dataupdater.Main dosageenddate update > fmk-data-updater_updatestage.log



Pak tar fra /pack/temp/fmk-data-updater med stage-propertyfiler, gemmes i /pack/temp/stage/fmk-data-updater-eclipse.tar:
tar -cf /pack/temp/stage/fmk-data-updater-eclipse.tar fmk-data-updater-eclipse_lib/* fmk-data-updater-eclipse.jar stage/*.properties *.js
 
Pak tar fra /pack/temp/fmk-data-updater med prod-propertyfiler, gemmes i /pack/temp/prod/fmk-data-updater-eclipse.tar:
tar -cf /pack/temp/prod/fmk-data-updater-eclipse.tar fmk-data-updater-eclipse_lib/* fmk-data-updater-eclipse.jar prod/*.properties *.js

Køres således: 
java -cp "stage:fmk-data-updater-eclipse_lib:fmk-data-updater-eclipse.jar" dk.medicinkortet.dataupdater.Main dosageenddate test

Eller i test1 udgaven (da .properties filerne ligger sammen med jar'en):
java -cp ".:fmk-data-updater-eclipse_lib:fmk-data-updater-eclipse.jar" dk.medicinkortet.dataupdater.Main dosageenddate test

