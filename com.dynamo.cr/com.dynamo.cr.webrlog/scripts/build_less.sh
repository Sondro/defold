mkdir -p war/bootstrap/css
recess --compile ../com.dynamo.cr.web2/less/bootstrap.less > war/bootstrap/css/bootstrap.css
recess --compress ../com.dynamo.cr.web2/less/bootstrap.less > war/bootstrap/css/bootstrap.min.css
recess --compile ../com.dynamo.cr.web2/less/responsive.less > war/bootstrap/css/bootstrap-responsive.css
recess --compress ../com.dynamo.cr.web2/less/responsive.less > war/bootstrap/css/bootstrap-responsive.min.css
