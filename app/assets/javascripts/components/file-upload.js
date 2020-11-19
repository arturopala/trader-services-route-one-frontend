$(document).ready(function () {
  var Upload = function () {
    this.$form = $('.file-upload');

    if (!this.$form.length) {
      return;
    }

    this.config = {
      retryDelayMs: this.$form.data('file-upload-retry-delay-ms') || 1000,
      uploadUrl: this.$form.attr('action'),
      successUrl: this.$form.data('file-upload-redirect-success-url'),
      failureUrl: this.$form.data('file-upload-redirect-failure-url'),
      checkStatusUrl: this.$form.data('file-upload-check-status-url'),
      ariaLiveMessage: this.$form.data('file-upload-aria-live-message')
    };

    this.cacheTemplates();
    this.cacheElements();
    this.bindEvents();
  };

  Upload.prototype.cacheTemplates = function () {
    this.ariaLiveMessageTpl = '<p class="govuk-body">' + this.config.ariaLiveMessage + '</p>';
  };

  Upload.prototype.cacheElements = function () {
    this.$loadingContainer = this.$form.find('.file-upload__loading-container');
    this.$spinner = this.$form.find('.file-upload__spinner');
    this.$submit = this.$form.find('.file-upload__submit');
  };

  Upload.prototype.bindEvents = function () {
    this.$form.submit($.proxy(this.handleSubmit, this));
  };

  Upload.prototype.handleSubmit = function (e) {
    e.preventDefault();

    this.showLoadingMessage();
    this.submitForm();
  };

  Upload.prototype.showLoadingMessage = function () {
    this.$submit.prop('disabled', true);
    this.$spinner.removeClass('hidden');
    this.$loadingContainer.prepend(this.ariaLiveMessageTpl);
  };

  Upload.prototype.submitForm = function () {
    $.ajax({
      url: this.config.uploadUrl,
      type: "POST",
      data: new FormData(this.$form.get(0)),
      processData: false,
      contentType: false,
      crossDomain: true
    })
    .fail($.proxy(this.handleUploadFormError, this))
    .done($.proxy(this.handleUploadFormCompleted, this));
  };

  Upload.prototype.handleUploadFormError = function () {
    window.location.href = this.config.failureUrl;
  };

  Upload.prototype.handleUploadFormCompleted = function () {
    this.requestUploadStatus();
  };

  Upload.prototype.requestUploadStatus = function () {
    $.ajax({
      url: this.config.checkStatusUrl,
      type: 'GET',
      data: {},
      processData: false,
      contentType: false,
      crossDomain: true
    })
    .fail($.proxy(this.delayedRequestUploadStatus, this))
    .done($.proxy(this.handleRequestUploadStatusCompleted, this));
  };

  Upload.prototype.delayedRequestUploadStatus = function () {
    window.setTimeout($.proxy(this.requestUploadStatus, this),
      this.config.retryDelayMs);
  };

  Upload.prototype.handleRequestUploadStatusCompleted = function (response) {
    switch (response['fileStatus']) {
      case 'ACCEPTED':
        window.location.href = this.config.successUrl;
        break;

      case 'FAILED':
      case 'REJECTED':
        window.location.href = this.config.failureUrl;
        break;

      case 'WAITING':
      default:
        this.delayedRequestUploadStatus();
        break;
    }
  };

  new Upload();
});