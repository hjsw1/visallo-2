
define([
    'flight/lib/component',
    '../withPopover',
    './formattedFile.hbs',
    'detail/dropdowns/propertyForm/justification',
    'configuration/plugins/registry',
    'util/visibility/edit',
    'util/ontology/conceptSelect',
    'util/formatters',
    'util/withFormFieldErrors',
    'util/withDataRequest',
    'util/component/attacher'
], function(
    defineComponent,
    withPopover,
    fileTemplate,
    Justification,
    registry,
    VisibilityEditor,
    ConceptSelector,
    F,
    withFormFieldErrors,
    withDataRequest,
    Attacher) {
    'use strict';

    /**
     * Create custom endpoints to ingest content into Visallo. A Long running process
     * will process the loading of content asynchronously. Plugins register a
     * [`CloudResourceSource`](https://github.com/visallo/visallo/blob/master/core/core/src/main/java/org/visallo/core/ingest/cloud/CloudResourceSource.java) that returns items given a JSON configuration
     * object generated by a custom component in the UI.
     *
     * Cloud import items are available in the file import popover.
     *
     * Visallo includes a plugin for [Amazon S3](https://github.com/visallo/visallo/tree/master/web/plugins/ingest-cloud-s3)
     *
     * @param {string} identifier Unique identifier string for this cloud source item.
     * Usually set to the class name of the `CloudResourceSource` implementation.
     *
     * Also used for the user displayable title in UI. Plugins should register a message bundle with:
     * `[identifier].title=My Cloud Source Title`
     *
     * @param {string} componentPath Path to {@link org.visallo.ingest.cloud~Component}
     */
    registry.documentExtensionPoint('org.visallo.ingest.cloud',
        'Specify cloud destinations for ingestion',
        function(e) {
            return _.isString(e.identifier) && _.isString(e.componentPath)
        }
    );

    return defineComponent(FileImport, withPopover, withFormFieldErrors, withDataRequest);

    function FileImport() {

        this.defaultAttrs({
            importSelector: '.fileimport',
            cancelSelector: '.filecancel',
            toggleCheckboxSelector: '.toggle-collapsed',
            importFileButtonSelector: 'button.file-select',
            importFileSelector: '.create-form input.file',
            importCloudSelector: 'button.cloud-select',
            cloudExtensionsSelector: '.cloud-extensions li',
            visibilityInputSelector: '.visibility',
            justificationSelector: '.justification',
            conceptSelector: '.concept-container',
            singleSelector: '.single',
            singleVisibilitySelector: '.single .visibility',
            individualVisibilitySelector: '.individual-visibility'
        });

        this.before('teardown', function() {
            this.popover
                .find(this.attr.justificationSelector)
                .add(this.attr.visibilityInputSelector)
                .add(this.attr.conceptSelector)
                .teardownAllComponents();
        });

        this.after('teardown', function() {
            if (this.request && this.request.cancel) {
                this.request.cancel();
            }
        });

        this.getTitle = function(files, stringType) {
            if (files.length) {
                var pluralString = i18n('popovers.file_import.files.' + (
                    files.length === 1 || stringType ? 'one' : 'some'
                ), files.length);
                return i18n('popovers.file_import.title', pluralString);
            }
            return i18n('popovers.file_import.nofile.title');
        };

        this.before('initialize', function(node, config) {
            config.template = 'fileImport/template';

            if (!config.files) config.files = [];

            config.hasFile = config.files.length > 0;
            config.cloudExtensions = registry.extensionsForPoint('org.visallo.ingest.cloud');
            config.hasCloudExtensions = config.cloudExtensions.length > 0;
            config.multipleFiles = config.files.length > 1;
            config.title = this.getTitle(config.files, config.stringType);

            this.after('setupWithTemplate', function() {
                var self = this;

                this.visibilitySource = null;
                this.visibilitySources = new Array(config.files.length);
                this.concepts = new Array(config.files.length);

                this.on(this.popover, 'visibilitychange', this.onVisibilityChange);
                this.on(this.popover, 'justificationchange', this.onJustificationChange);
                this.on(this.popover, 'conceptSelected', this.onConceptChange);

                this.enterShouldSubmit = 'importSelector';

                this.on(this.popover, 'click', {
                    importSelector: this.onImport,
                    cancelSelector: this.onCancel,
                    importCloudSelector: this.onImportCloud,
                    cloudExtensionsSelector: this.onCloudExtension
                });

                this.on(this.popover, 'change', {
                    toggleCheckboxSelector: this.onCheckboxCopy,
                    importFileSelector: this.onFileChange
                })

                this.setFiles(this.attr.files);
                this.checkValid();
            })
        });

        this.onImportCloud = function(event) {
            this.popover.find('.import-cloud-form')
                .html($('<ul>').addClass('nav nav-list cloud-extensions')
                    .html(
                        this.attr.cloudExtensions.map(e =>
                            $('<li><a>')
                                .attr('data-path', e.componentPath)
                                .attr('data-identifier', e.identifier)
                                .find('a').text(i18n(e.identifier + '.title'))
                                .end()
                        )
                    )
                );
            this.popover.find('.show-create-form').removeClass('show-create-form');
        };

        this.onCloudExtension = function(event) {
            var self = this,
                $li = $(event.target).closest('li'),
                path = $li.attr('data-path'),
                identifier = $li.attr('data-identifier'),
                /**
                 * Flight or React component that provides the configuration
                 * for this cloud resource.
                 *
                 * @typedef org.visallo.ingest.cloud~Component
                 * @property {function} onImport Trigger the upload given the
                 * configuration provided by the user and configuration
                 * component.
                 * @fires org.visallo.ingest.cloud#cloudImported
                 */
                attacher = Attacher()
                    .node(this.popover.find('.import-cloud-form').empty())
                    .path(path)
                    .behavior({
                        onImport: function(attacher, importConfig) {
                            self.dataRequest('vertex', 'cloudImport', identifier, importConfig)
                                .then(() => {
                                    self.trigger('showActivityDisplay');
                                })
                            self.teardown();
                        }
                    })
                    .legacyMapping({
                        /**
                         * Flight event to fire when upload should be triggered
                         *
                         * @event org.visallo.ingest.cloud#cloudImported
                         */
                        onImport: 'cloudImported'
                    })

            self.before('teardown', () => attacher.teardown())
            attacher.attach();
        };

        this.onFileChange = function(event) {
            var files = event.target.files;
            if (files.length) {
                this.setFiles(files);
            }
        };

        this.setFiles = function(files) {
            this.attr.files = files;
            this.popover.find('.popover-title').text(this.getTitle(files, this.attr.stringType));
            this.popover.find(this.attr.importSelector).text(files.length ?
                i18n('popovers.file_import.button.import') :
                i18n('popovers.file_import.button.nofile.import')
            );
            var $single = this.popover.find(this.attr.singleSelector).hide();

            if (files.length === 0) {
                this.popover.find(this.attr.importFileButtonSelector).toggle(!this.attr.stringType);
                this.popover.find(this.attr.toggleCheckboxSelector).hide();
                this.popover.find(this.attr.individualVisibilitySelector).hide();
                $single.html(fileTemplate({
                    name: this.attr.stringType || undefined,
                    index: 'collapsed',
                    justification: true
                }))
            } else if (files.length === 1) {
                this.popover.find(this.attr.importFileButtonSelector).hide();
                this.popover.find(this.attr.toggleCheckboxSelector).hide();
                this.popover.find(this.attr.individualVisibilitySelector).hide();
                $single.html(fileTemplate({
                    name: files[0].name,
                    size: F.bytes.pretty(files[0].size, 0),
                    index: 'collapsed'
                }));
            } else {
                this.popover.find(this.attr.importFileButtonSelector).hide();
                this.popover.find(this.attr.toggleCheckboxSelector).show();
                $single.html(fileTemplate({
                    name: i18n('popovers.file_import.files.some', files.length),
                    size: F.bytes.pretty(_.chain(files)
                            .map(_.property('size'))
                            .reduce(function(memo, num) {
                                return memo + num;
                            }, 0)
                            .value()),
                    index: 'collapsed'
                }));
                this.popover.find(this.attr.individualVisibilitySelector)
                    .hide()
                    .html(
                        $.map(files, function(file, i) {
                            return fileTemplate({
                                name: file.name,
                                size: F.bytes.pretty(file.size, 0),
                                index: i
                            })
                        })
                    )
            }

            Justification.attachTo(this.popover.find(this.attr.justificationSelector).eq(0));
            VisibilityEditor.attachTo(this.popover.find(this.attr.visibilityInputSelector));
            ConceptSelector.attachTo(this.popover.find(this.attr.conceptSelector).eq(0), {
                focus: true,
                defaultText: files.length ?
                    i18n('popovers.file_import.concept.placeholder') :
                    i18n('popovers.file_import.concept.nofile.placeholder')
            });
            ConceptSelector.attachTo(this.popover.find(this.attr.conceptSelector), {
                defaultText: i18n('popovers.file_import.concept.placeholder')
            });
            $single.show();

            this.positionDialog();
        }

        this.onCheckboxCopy = function(e) {
            var $checkbox = $(e.target),
                checked = $checkbox.is(':checked');

            this.popover.find(this.attr.singleSelector).toggle(checked);
            this.popover.toggleClass('collapseVisibility', checked);
            this.popover.find(this.attr.individualVisibilitySelector).toggle(!checked);
            this.popover.find('.errors').empty();
            _.delay(this.positionDialog.bind(this), 50);
            this.checkValid();
        };

        this.onConceptChange = function(event, data) {
            var concept = data.concept,
                index = $(event.target)
                    .closest(this.attr.conceptSelector)
                    .data('concept', concept)
                    .data('fileIndex');

            if (index === 'collapsed') {
                this.concept = concept;
            } else {
                this.concepts[index] = concept;
            }

            this.checkValid();
        };

        this.onJustificationChange = function(event, data) {
            this.justification = data;
            this.checkValid();
        };

        this.onVisibilityChange = function(event, data) {
            var index = $(event.target)
                .data('visibility', data)
                .data('fileIndex');

            if (index === 'collapsed') {
                this.visibilitySource = data;
            } else {
                this.visibilitySources[index] = data;
            }

            this.checkValid();
        };

        this.checkValid = function() {
            var self = this;
            var collapsed = this.isVisibilityCollapsed(),
                isValid = collapsed ?
                    (this.visibilitySource && this.visibilitySource.valid &&
                     (this.attr.files.length || (
                         this.justification && this.justification.valid))
                    ) :
                    _.every(this.visibilitySources, _.property('valid'));

            if (collapsed) {
                this.popover.find(this.attr.singleVisibilitySelector).find('input').toggleClass('invalid', !isValid);
            } else {
                this.popover.find(this.attr.individualVisibilitySelector).find('.visibility').each(function() {
                    var $visibility = $(this);
                    var fileIndex = $visibility.data('fileIndex')
                    var visibilityValid = self.visibilitySources[fileIndex].valid;

                    $visibility.find('input').toggleClass('invalid', !visibilityValid);
                });
            }
            if (isValid && this.attr.files.length === 0 && !this.concept) {
                isValid = false;
            }

            this.popover.find(this.attr.importSelector).prop('disabled', !isValid);

            return isValid;
        };

        this.isVisibilityCollapsed = function() {
            var checkbox = this.popover.find('.checkbox input');

            return checkbox.length === 0 || checkbox.is(':checked');
        };

        this.onCancel = function() {
            this.teardown();
        };

        this.onImport = function() {
            if (!this.checkValid()) {
                return false;
            }

            var self = this,
                files = this.attr.files,
                button = this.popover.find('.btn-primary')
                    .text(files.length ?
                          i18n('popovers.file_import.importing') :
                          i18n('popovers.file_import.creating')
                    )
                    .prop('disabled', true),
                cancelButton = this.popover.find('.btn-default').show(),
                collapsed = this.isVisibilityCollapsed(),
                conceptValue = collapsed ?
                    this.concept && this.concept.id :
                    _.map(this.concepts, function(c) {
                        return c && c.id || '';
                    }),
                visibilityValue = collapsed ?
                    this.visibilitySource.value :
                    _.map(this.visibilitySources, _.property('value'));

            this.attr.teardownOnTap = false;

            var req = _.partial(this.dataRequest.bind(this), 'vertex', _, _, conceptValue, visibilityValue);
            if (files.length) {
                this.request = req('importFiles', files);
            } else if (this.attr.string) {
                this.request = req('importFileString', {
                    string: this.attr.string,
                    type: this.attr.stringMimeType
                });
            } else {
                this.request = req('create', this.justification);
            }

            if (_.isFunction(this.request.progress)) {
                this.request
                    .progress(function(complete) {
                        var percent = Math.round(complete * 100);
                        button.text(percent + '% ' + (
                            files.length ?
                                  i18n('popovers.file_import.importing') :
                                  i18n('popovers.file_import.creating')
                        ));
                    })
            }

            this.request.then(function(result) {
                var vertexIds = _.isArray(result.vertexIds) ? result.vertexIds : [result.id];
                var page = self.attr.anchorTo.page;

                self.trigger('fileImportSuccess', { vertexIds, position: page });
                self.trigger('selectObjects', { vertexIds })

                _.defer(function() {
                    self.teardown();
                })
            })
            .catch(function(error) {
                self.attr.teardownOnTap = true;
                // TODO: fix error
                self.markFieldErrors(error || 'Unknown Error', self.popover);
                cancelButton.hide();
                button.text(files.length ?
                        i18n('popovers.file_import.button.import') :
                        i18n('popovers.file_import.button.nofile.import')
                    )
                    .removeClass('loading')
                    .prop('disabled', false)

                _.defer(self.positionDialog.bind(self));
            })
        }
    }
});
