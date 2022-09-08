-include= ~${workspace}/cnf/resources/bnd/feature.props
symbolicName=io.openliberty.nosql-1.0
visibility=public
singleton=true
IBM-ShortName: nosql-1.0
IBM-API-Package: \
  jakarta.nosql; type="spec",\
  jakarta.nosql.column; type="spec",\
  jakarta.nosql.document; type="spec",\
  jakarta.nosql.keyvalue; type="spec",\
  jakarta.nosql.mapping; type="spec",\
  jakarta.nosql.mapping.column; type="spec",\
  jakarta.nosql.mapping.document; type="spec",\
  jakarta.nosql.mapping.keyvalue; type="spec",\
  jakarta.nosql.query; type="spec"
Subsystem-Name: Jakarta NoSQL 1.0
#TODO com.ibm.websphere.appserver.eeCompatible-11.0
#TODO io.openliberty.jakartaeePlatform-11.0
-features=\
  io.openliberty.jakarta.nosql-1.0
kind=noship
edition=full
WLP-Activation-Type: parallel
